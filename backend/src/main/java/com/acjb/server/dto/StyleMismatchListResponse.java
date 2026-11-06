package com.acjb.server.dto;

import java.util.List;

import com.acjb.server.dto.QaIssueResponse.StyleMismatchView;

/** openapi StyleMismatchListResponse.data — 페이지네이션은 v1에 없어 total은 items 개수와 같다 */
public record StyleMismatchListResponse(List<StyleMismatchView> items, int total) {
}
