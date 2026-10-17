package com.payverse.bill.dto;

import com.payverse.bill.entity.BillCategory;

import java.math.BigDecimal;

public record BillerResponse(String code, BillCategory category, String name, String accountLabel, String accountPattern,
                             boolean fetchSupported, BigDecimal minAmount, BigDecimal maxAmount) {
}
