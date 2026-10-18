package com.payverse.transaction.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransactionMapperTest {

    @Test
    void payeeLabelAddsTheNameOnce() {
        assertThat(TransactionMapper.payeeLabel("Ravi Sharma", "ravi@payverse")).isEqualTo("Ravi Sharma (ravi@payverse)");
        assertThat(TransactionMapper.payeeLabel("Ravi Sharma", "Ravi Sharma (******6488)")).isEqualTo("Ravi Sharma (******6488)");
        assertThat(TransactionMapper.payeeLabel(null, "******6488")).isEqualTo("******6488");
    }
}
