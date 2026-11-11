package com.yuyay.attachment.dto;

import jakarta.validation.constraints.NotNull;

public record DocumentScopeSwitchRequest(@NotNull Boolean enabled) {}
