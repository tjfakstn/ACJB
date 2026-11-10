package com.acjb.server.common;

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 컨트롤러에서 나온 예외를 openapi ErrorResponse 형태({success:false, error:{code, message}})로 응답한다(N12).
 * Security 단계 거부(401·403)는 JsonErrorResponder가 맡는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiResponses.error(e.getCode(), e.getMessage()));
    }

    /** JSON 형식 오류, 타입이 다른 값(예: repro_steps에 문자열) */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponses.error(ErrorCodes.VALIDATION_FAILED, "요청 본문을 읽을 수 없습니다. JSON 형식과 각 필드의 타입을 확인하세요."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponses.error(ErrorCodes.VALIDATION_FAILED, e.getName() + " 값이 올바르지 않습니다."));
    }

    /** 잠금 대기 시간 초과·데드락 — 행 잠금 뒤에도 드물게 남을 수 있다. 데이터는 롤백됐으니 다시 시도하면 된다 */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleLock(PessimisticLockingFailureException e) {
        log.warn("동시 요청 충돌: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponses.error(ErrorCodes.CONCURRENT_UPDATE, "다른 요청과 동시에 처리돼 반영하지 못했습니다. 다시 시도하세요."));
    }

    /**
     * 그 밖의 오류. Spring MVC가 만든 4xx(없는 경로 404, 허용하지 않는 메서드 405 등 — ErrorResponse 구현)는
     * 상태를 그대로 두고 본문만 envelope로 바꾼다. 나머지는 500이고, 내부 메시지는 응답에 싣지 않고 로그에만 남긴다
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        if (e instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String code = known == HttpStatus.NOT_FOUND ? ErrorCodes.NOT_FOUND
                : known == HttpStatus.BAD_REQUEST ? ErrorCodes.VALIDATION_FAILED
                : known != null ? known.name().toUpperCase(Locale.ROOT) : "HTTP_" + status.value();
            String message = known == HttpStatus.NOT_FOUND ? "요청한 경로를 찾을 수 없습니다."
                : "요청을 처리할 수 없습니다(" + status.value() + ").";
            return ResponseEntity.status(status).body(ApiResponses.error(code, message));
        }
        log.error("처리되지 않은 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponses.error(ErrorCodes.INTERNAL_ERROR, "서버 오류가 발생했습니다."));
    }
}
