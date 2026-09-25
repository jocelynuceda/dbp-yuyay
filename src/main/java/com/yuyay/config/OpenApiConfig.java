package com.yuyay.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    private final AppProperties properties;

    @Bean
    public OpenAPI yuyayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Yuyay API")
                        .version("v1")
                        .description("""
                                Registro de salud declarativo compartido entre cuidadores.

                                Autenticación: obtén un token en POST /api/v1/auth/login y pulsa Authorize.
                                El rol global (USER, ADMIN, DELEGATE) autentica; el acceso a cada persona lo
                                decide su CareRelationship activa o su Delegation vigente.""")
                        .contact(new Contact().name("Grupo 3 · CS2031 · UTEC"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .servers(List.of(new Server().url(properties.baseUrl()).description("Servidor actual")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
