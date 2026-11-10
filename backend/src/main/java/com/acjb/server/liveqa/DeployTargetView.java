package com.acjb.server.liveqa;

import java.util.Locale;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** openapi DeployTarget (AC29) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeployTargetView(String url, String source, String manualUrl, Boolean embeddable, String embedBlockedReason) {

    static DeployTargetView of(DeployTargetService.Target target, String manualUrl, EmbedChecker.Result embed) {
        return new DeployTargetView(target.url(), target.source().name().toLowerCase(Locale.ROOT), manualUrl,
            embed.embeddable(), embed.reason());
    }
}
