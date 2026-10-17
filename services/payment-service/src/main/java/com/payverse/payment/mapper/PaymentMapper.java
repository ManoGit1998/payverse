package com.payverse.payment.mapper;

import com.payverse.common.money.Money;
import com.payverse.payment.dto.CollectResponse;
import com.payverse.payment.dto.DecodedQrResponse;
import com.payverse.payment.dto.InternalDecodedQrResponse;
import com.payverse.payment.dto.MoneyDto;
import com.payverse.payment.dto.PgChargeResponse;
import com.payverse.payment.dto.QrResponse;
import com.payverse.payment.dto.VpaResponse;
import com.payverse.payment.entity.CollectRequest;
import com.payverse.payment.entity.PgOrder;
import com.payverse.payment.entity.QrCode;
import com.payverse.payment.entity.Vpa;
import com.payverse.payment.service.QrService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;

/** Entities → API responses (MapStruct). */
@Mapper
public interface PaymentMapper {

    @Mapping(target = "vpa", source = "handle")
    @Mapping(target = "status", expression = "java(vpa.getStatus().name())")
    VpaResponse toResponse(Vpa vpa);

    CollectResponse toResponse(CollectRequest request);

    @Mapping(target = "imageUrl", expression = "java(\"/api/v1/qr/\" + qr.getId() + \".png\")")
    QrResponse toResponse(QrCode qr);

    DecodedQrResponse toResponse(QrService.DecodedQr decoded);

    InternalDecodedQrResponse toInternal(QrService.DecodedQr decoded);

    @Mapping(target = "orderId", source = "id")
    @Mapping(target = "providerReference", source = "providerRef")
    PgChargeResponse toResponse(PgOrder order);

    default MoneyDto money(BigDecimal amount) {
        return amount == null ? null : MoneyDto.of(Money.inr(amount));
    }

    default MoneyDto money(Money amount) {
        return MoneyDto.of(amount);
    }
}
