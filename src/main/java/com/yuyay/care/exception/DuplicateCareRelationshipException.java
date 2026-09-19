package com.yuyay.care.exception;

public class DuplicateCareRelationshipException extends RuntimeException {
    public DuplicateCareRelationshipException(Long userId, Long careSubjectId) {
        super("Ya existe una relación entre el usuario " + userId + " y el sujeto " + careSubjectId);
    }
}