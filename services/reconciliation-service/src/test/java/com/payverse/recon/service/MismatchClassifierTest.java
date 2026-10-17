package com.payverse.recon.service;

import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.model.ReconPair;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MismatchClassifierTest {

    static final BigDecimal TEN = new BigDecimal("10.00");

    @Test
    void classifiesPairs() {
        ReconJob job = ReconJob.PG_VS_LEDGER;
        assertThat(MismatchClassifier.classify(job, new ReconPair("r", TEN, true, new BigDecimal("10"), true))).isNull();
        assertThat(MismatchClassifier.classify(job, new ReconPair("r", TEN, true, new BigDecimal("10.01"), false))).isEqualTo("AMOUNT_MISMATCH");
        assertThat(MismatchClassifier.classify(job, new ReconPair("r", TEN, true, null, null))).isEqualTo("CHARGED_NOT_CREDITED");
        assertThat(MismatchClassifier.classify(job, new ReconPair("r", null, null, TEN, true))).isEqualTo("CREDITED_WITHOUT_CHARGE");
        assertThat(MismatchClassifier.classify(job, new ReconPair("r", null, null, TEN, false))).isNull(); // belongs to the next day
    }
}
