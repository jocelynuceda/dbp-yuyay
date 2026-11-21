package com.yuyay.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    private CorsConfiguration configurationFor(List<String> origins, List<String> patterns) {
        AppProperties properties = new AppProperties(
                null, null, null, new AppProperties.Cors(origins, patterns), null, null);
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) new CorsConfig(properties).corsConfigurationSource();
        return source.getCorsConfigurations().get("/**");
    }

    @Test
    void permiteOrigenExactoDeAllowedOrigins() {
        CorsConfiguration config = configurationFor(List.of("http://localhost:5173"), List.of());

        assertThat(config.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    }

    @Test
    void permiteOrigenExactoDeAllowedOriginPatterns() {
        CorsConfiguration config = configurationFor(List.of(), List.of("https://yuyay-web.vercel.app"));

        assertThat(config.checkOrigin("https://yuyay-web.vercel.app")).isEqualTo("https://yuyay-web.vercel.app");
    }

    @Test
    void permiteVistaPreviaDeVercelPorComodin() {
        CorsConfiguration config = configurationFor(
                List.of(), List.of("https://yuyay-frontend-*-jocelynucedas-projects.vercel.app"));

        assertThat(config.checkOrigin("https://yuyay-frontend-abc123-jocelynucedas-projects.vercel.app"))
                .isEqualTo("https://yuyay-frontend-abc123-jocelynucedas-projects.vercel.app");
    }

    @Test
    void rechazaOrigenNoConfigurado() {
        CorsConfiguration config = configurationFor(
                List.of("http://localhost:5173"), List.of("https://yuyay-web.vercel.app"));

        assertThat(config.checkOrigin("https://atacante.example")).isNull();
    }

    @Test
    void combinaAmbasListasSinDuplicadosNiBlancos() {
        AppProperties.Cors cors = new AppProperties.Cors(
                List.of("http://localhost:5173", "  ", "https://yuyay-web.vercel.app"),
                List.of("https://yuyay-web.vercel.app", " https://yuyay-frontend.vercel.app "));

        assertThat(cors.originPatterns()).containsExactly(
                "http://localhost:5173",
                "https://yuyay-web.vercel.app",
                "https://yuyay-frontend.vercel.app");
    }

    @Test
    void permiteCredencialesSinComodinTotal() {
        CorsConfiguration config = configurationFor(List.of("http://localhost:5173"), List.of());

        assertThat(config.getAllowCredentials()).isTrue();
        assertThat(config.getAllowedOrigins()).isNull();
        assertThat(config.getAllowedOriginPatterns()).doesNotContain("*");
    }
}
