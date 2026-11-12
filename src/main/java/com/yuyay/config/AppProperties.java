package com.yuyay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
    /**
     * allowedOrigins: orígenes exactos. allowedOriginPatterns: admite comodines, para los dominios
     * de Vercel (producción y las vistas previas de cada rama). Vercel reenvía el Origin del navegador
     * al hacer de proxy de /api, así que esos dominios también deben estar permitidos.
     */
    public record Cors(List<String> allowedOrigins, List<String> allowedOriginPatterns) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
        }
    }
    public record Mail(String from, String resendApiKey) {}
    public record Admin(String email, String password, String name) {}
}
