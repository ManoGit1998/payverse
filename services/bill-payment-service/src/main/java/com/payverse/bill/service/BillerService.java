package com.payverse.bill.service;

import com.payverse.bill.entity.BillCategory;
import com.payverse.bill.entity.Biller;
import com.payverse.bill.exception.BillErrorCode;
import com.payverse.bill.model.FetchedBill;
import com.payverse.bill.repository.BillerRepository;
import com.payverse.common.error.PayverseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Biller catalogue, account validation and bill fetch. */
@Service
public class BillerService {

    private final BillerRepository billers;
    private final BillerGateway gateway;

    public BillerService(BillerRepository billers, BillerGateway gateway) {
        this.billers = billers;
        this.gateway = gateway;
    }

    @Transactional(readOnly = true)
    public List<Biller> list(BillCategory category) {
        return category == null ? billers.findByActiveTrueOrderByCategoryAscNameAsc() : billers.findByCategoryAndActiveTrueOrderByNameAsc(category);
    }

    @Transactional(readOnly = true)
    public Biller get(String code) {
        return billers.findByCode(code == null ? "" : code.trim().toUpperCase()).filter(Biller::isActive)
                .orElseThrow(() -> new PayverseException(BillErrorCode.BILL_BILLER_NOT_FOUND, null));
    }

    /** @return the normalised account ref, or throws BILL_INVALID_ACCOUNT */
    public String validAccount(Biller biller, String accountRef) {
        if (!biller.acceptsAccount(accountRef)) {
            throw new PayverseException(BillErrorCode.BILL_INVALID_ACCOUNT, "Enter a valid " + biller.getAccountLabel().toLowerCase());
        }
        return Biller.normalize(accountRef);
    }

    public FetchedBill fetch(String billerCode, String accountRef) {
        Biller biller = get(billerCode);
        if (!biller.isFetchSupported()) {
            throw new PayverseException(BillErrorCode.BILL_FETCH_NOT_SUPPORTED, biller.getName() + " is a recharge: choose an amount");
        }
        String account = validAccount(biller, accountRef);
        try {
            return gateway.fetch(biller, account);
        } catch (BillerGateway.AccountNotFoundException e) {
            throw new PayverseException(BillErrorCode.BILL_ACCOUNT_NOT_FOUND, e.getMessage());
        } catch (BillerGateway.BillerUnavailableException e) {
            throw new PayverseException(BillErrorCode.BILL_BILLER_UNAVAILABLE, e.getMessage());
        }
    }
}
