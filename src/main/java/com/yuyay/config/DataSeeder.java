package com.yuyay.config;

import com.yuyay.health.entity.HealthCategory;
import com.yuyay.health.repository.HealthCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSeeder {
    private final HealthCategoryRepository categories;

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
}
