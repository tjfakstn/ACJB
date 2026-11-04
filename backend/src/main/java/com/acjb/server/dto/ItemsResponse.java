package com.acjb.server.dto;

import java.util.List;

/** openapi ProjectListResponse·MemberListResponse의 data({items}) */
public record ItemsResponse<T>(List<T> items) {
}
