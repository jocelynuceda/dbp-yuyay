package com.yuyay.health.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Entity
@Table(name = "health_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthCategory {
    public static final String ALLERGY = "ALLERGY";
    public static final String CONDITION = "CONDITION";
    public static final String MEDICATION = "MEDICATION";
    public static final String IMMUNIZATION = "IMMUNIZATION";
    public static final String EPISODE = "EPISODE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @NotBlank
    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 255)
    private String description;
}
