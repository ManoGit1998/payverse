package com.payverse.bill.model;

import com.payverse.common.money.Money;

import java.time.LocalDate;

/** A bill as presented by the biller (customer name already masked). */
public record FetchedBill(String billerCode, String accountRefMasked, String customerName, String billNumber, Money amountDue,
                          LocalDate dueDate) {
}
