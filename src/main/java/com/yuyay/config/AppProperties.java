package com.yuyay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        String baseUrl,
        Cors cors,
        Mail mail,
        Admin admin
) {
    public record Jwt(String secret, long accessExpirationMinutes, long refreshExpirationDays) {}
    public record Cors(List<String> allowedOrigins) {}
    public record Mail(String from, String resendApiKey) {}
    public record Admin(String email, String password, String name) {}
}
