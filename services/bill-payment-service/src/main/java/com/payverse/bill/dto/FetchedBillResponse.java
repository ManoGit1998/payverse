package com.payverse.bill.dto;

import java.time.LocalDate;

public record FetchedBillResponse(String billerCode, String accountRefMasked, String customerName, String billNumber, MoneyDto amountDue,
                                  LocalDate dueDate) {
}
