package com.yuyay.config;

import com.yuyay.health.entity.HealthCategory;
import com.yuyay.health.repository.HealthCategoryRepository;
import com.yuyay.user.entity.Role;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSeeder {

    private final HealthCategoryRepository categories;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    @Bean
    ApplicationRunner seedHealthCategories() {
        return args -> {
            List<HealthCategory> seed = List.of(
                    HealthCategory.builder().code(HealthCategory.ALLERGY).name("Alergia").description("Alergias a medicamentos, alimentos u otros").build(),
                    HealthCategory.builder().code(HealthCategory.CONDITION).name("Condición").description("Diagnósticos y condiciones persistentes").build(),
                    HealthCategory.builder().code(HealthCategory.MEDICATION).name("Medicación").description("Medicamentos con dosis y frecuencia").build(),
                    HealthCategory.builder().code(HealthCategory.IMMUNIZATION).name("Vacuna").description("Vacunas aplicadas").build(),
                    HealthCategory.builder().code(HealthCategory.EPISODE).name("Episodio").description("Crisis, urgencias u otros eventos puntuales").build()
            );
            for (HealthCategory c : seed) {
                if (categories.findByCode(c.getCode()).isEmpty()) {
                    categories.save(c);
                    log.info("Categoría creada: {}", c.getCode());
                }
            }
        };
    }

    @Bean
    ApplicationRunner seedAdminUser() {
        return args -> {
            AppProperties.Admin admin = properties.admin();
            if (admin == null || admin.password() == null || admin.password().isBlank()) {
                log.info("ADMIN_PASSWORD no definido: no se crea el usuario administrador");
                return;
            }
            if (users.existsByEmailIgnoreCase(admin.email())) {
                return;
            }
            users.save(User.builder()
                    .email(admin.email().toLowerCase())
                    .passwordHash(passwordEncoder.encode(admin.password()))
                    .name(admin.name())
                    .role(Role.ADMIN)
                    .build());
            log.info("Usuario administrador creado: {}", admin.email());
        };
    }
}
