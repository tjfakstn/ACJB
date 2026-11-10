package com.acjb.server.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공통 응답 envelope(SPEC 6.1 N12: {success, data, error}).
 * 실패 응답에는 data 키를 넣지 않는다 — 거부 응답에 데이터가 실리지 않음을 형태로도 보장한다(골든 케이스 au-002·ng-010).
 */
public final class ApiResponses {

    private ApiResponses() {
    }

    public static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("error", null);
        return body;
    }

    public static Map<String, Object> error(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", error);
        return body;
    }
}
