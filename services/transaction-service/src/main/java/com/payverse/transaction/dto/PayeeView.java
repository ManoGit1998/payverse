package com.payverse.transaction.dto;

import com.payverse.transaction.entity.PayeeType;

public record PayeeView(PayeeType type, String ref, String name) {
}
