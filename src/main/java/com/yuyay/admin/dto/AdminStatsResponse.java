package com.yuyay.admin.dto;

public record AdminStatsResponse(
        long users,
        long careSubjects,
        long activeCareRelationships,
        long healthEntries,
        long activeDelegations
) {}
