package com.yuyay.care.controller;

import com.yuyay.care.dto.DelegateSessionResponse;
import com.yuyay.care.service.DelegationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/delegations")
@RequiredArgsConstructor
public class PublicDelegationController {

    private final DelegationService service;

    @PostMapping("/{token}/exchange")
    public DelegateSessionResponse exchange(@PathVariable String token) {
        return service.exchange(token);
    }
}
