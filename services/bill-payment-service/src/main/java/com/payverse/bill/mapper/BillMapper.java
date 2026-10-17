package com.payverse.bill.mapper;

import com.payverse.bill.dto.BillPaymentResponse;
import com.payverse.bill.dto.BillerResponse;
import com.payverse.bill.dto.FetchedBillResponse;
import com.payverse.bill.dto.MandateExecutionResponse;
import com.payverse.bill.dto.MandateResponse;
import com.payverse.bill.dto.MoneyDto;
import com.payverse.bill.entity.BillPayment;
import com.payverse.bill.entity.Biller;
import com.payverse.bill.entity.Mandate;
import com.payverse.bill.entity.MandateExecution;
import com.payverse.bill.model.FetchedBill;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(imports = MoneyDto.class)
public interface BillMapper {

    BillerResponse toResponse(Biller biller);

    @Mapping(target = "amountDue", expression = "java(MoneyDto.of(bill.amountDue()))")
    FetchedBillResponse toResponse(FetchedBill bill);

    @Mapping(target = "amount", expression = "java(MoneyDto.of(payment.getMoney()))")
    @Mapping(target = "refunded", expression = "java(payment.getRefundJournalId() != null)")
    BillPaymentResponse toResponse(BillPayment payment);

    @Mapping(target = "maxAmount", expression = "java(MoneyDto.of(mandate.getMaxMoney()))")
    @Mapping(target = "amount", expression = "java(MoneyDto.of(mandate.getFixedMoney()))")
    MandateResponse toResponse(Mandate mandate);

    MandateExecutionResponse toResponse(MandateExecution execution);
}
