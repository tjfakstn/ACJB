package com.acjb.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST /qa-issues/parse body */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParseRequest(String text) {
}
