package com.payverse.transaction.dto;

import java.util.List;

public record TransactionDetailResponse(TransactionResponse transaction, List<StatusChangeResponse> statusHistory) {
}
