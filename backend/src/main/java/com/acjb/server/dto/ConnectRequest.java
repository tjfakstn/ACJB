package com.acjb.server.dto;

/** PUT /projects/{projectId}/connections/figma|github — AC21 */
public record ConnectRequest(String url) {
}
