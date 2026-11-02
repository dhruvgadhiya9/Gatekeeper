package com.gatekeeper.qaplatform.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param productionDomains domains that must never be tested, including their subdomains */
@ConfigurationProperties("app.staging")
public record StagingProperties(List<String> productionDomains) {

    public StagingProperties {
        productionDomains = productionDomains == null
                ? List.of()
                : productionDomains.stream()
                        .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                        .filter(domain -> !domain.isEmpty())
                        .toList();
    }
}
