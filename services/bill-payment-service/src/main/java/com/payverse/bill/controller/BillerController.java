package com.payverse.bill.controller;

import com.payverse.bill.dto.BillerResponse;
import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.mapper.BillMapper;
import com.payverse.bill.service.BillerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/billers")
@Tag(name = "Billers")
public class BillerController {

    private final BillerService billers;
    private final BillMapper mapper;

    public BillerController(BillerService billers, BillMapper mapper) {
        this.billers = billers;
        this.mapper = mapper;
    }

    @Operation(summary = "Billers (MOCK), optionally by category")
    @GetMapping
    public List<BillerResponse> list(@RequestParam(required = false) BillCategory category) {
        return billers.list(category).stream().map(mapper::toResponse).toList();
    }

    @Operation(summary = "One biller")
    @GetMapping("/{code}")
    public BillerResponse get(@PathVariable String code) {
        return mapper.toResponse(billers.get(code));
    }
}
