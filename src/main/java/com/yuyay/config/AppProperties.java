package com.yuyay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.stream.Stream;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        String baseUrl,
        String frontendUrl,
        Cors cors,
        Mail mail,
        Admin admin
) {
    public AppProperties {
        if (frontendUrl != null) frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    public record Jwt(String secret, long accessExpirationMinutes, long refreshExpirationDays) {}
    public record Cors(List<String> allowedOrigins, List<String> allowedOriginPatterns) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
        }

        public List<String> originPatterns() {
            return Stream.concat(allowedOrigins.stream(), allowedOriginPatterns.stream())
                    .filter(origin -> origin != null && !origin.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }
    }
    public record Mail(String from, String resendApiKey) {}
    public record Admin(String email, String password, String name) {}
}
