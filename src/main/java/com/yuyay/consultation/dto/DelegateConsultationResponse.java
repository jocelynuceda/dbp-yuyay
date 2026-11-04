package com.yuyay.consultation.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/** Sin las notas: pueden mencionar información de categorías que no se compartieron. */
public record DelegateConsultationResponse(Long id, LocalDate date, LocalTime time, String reason) {}
