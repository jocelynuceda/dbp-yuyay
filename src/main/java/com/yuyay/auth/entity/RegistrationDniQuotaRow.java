package com.yuyay.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;

@Entity
@Table(name = "registration_dni_quotas")
@IdClass(RegistrationDniQuotaRow.Key.class)
public class RegistrationDniQuotaRow {
    public record Key(String bucket, LocalDate quotaDay) implements Serializable {}

    @Id
    @Column(length = 64)
    private String bucket;

    @Id
    @Column(name = "quota_day")
    private LocalDate quotaDay;

    @Column(name = "used_count", nullable = false)
    private int usedCount;
}
