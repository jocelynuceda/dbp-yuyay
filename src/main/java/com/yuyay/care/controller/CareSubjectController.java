package com.yuyay.care.controller;

import com.yuyay.care.dto.CareSubjectCreateRequest;
import com.yuyay.care.dto.CareSubjectResponse;
import com.yuyay.care.dto.CareSubjectUpdateRequest;
import com.yuyay.care.service.CareSubjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/care-subjects")
@RequiredArgsConstructor
public class CareSubjectController {

    private final CareSubjectService service;

    @PostMapping
    public ResponseEntity<CareSubjectResponse> create(@Valid @RequestBody CareSubjectCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping
    public List<CareSubjectResponse> listMine() {
        return service.listMine();
    }

    @GetMapping("/{id}")
    public CareSubjectResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public CareSubjectResponse update(@PathVariable Long id,
                                      @Valid @RequestBody CareSubjectUpdateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}