package com.acjb.server.common;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 컨트롤러에서 나온 예외를 ErrorResponse 형태로 응답한다. Security 단계 거부(401·403)는 JsonErrorResponder가 맡는다 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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
}
