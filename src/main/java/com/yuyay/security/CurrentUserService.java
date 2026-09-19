package com.yuyay.security;

import com.yuyay.exception.InvalidTokenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    public Long getCurrentUserId() {
        return getUserPrincipal().getId();
    }

    public UserPrincipal getUserPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        throw new InvalidTokenException("No hay un usuario autenticado");
    }

    public DelegatePrincipal getDelegatePrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof DelegatePrincipal principal) {
            return principal;
        }
        throw new InvalidTokenException("No hay un delegado autenticado");
    }
}
