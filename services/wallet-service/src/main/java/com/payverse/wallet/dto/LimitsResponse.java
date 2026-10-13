package com.payverse.wallet.dto;

/** Current tier limits and usage (INR, plain strings). */
public record LimitsResponse(String maxBalance, String monthlyLoadLimit, String monthlyLoadUsed, String dailyDebitLimit,
                             String dailyDebitUsed) {
}
