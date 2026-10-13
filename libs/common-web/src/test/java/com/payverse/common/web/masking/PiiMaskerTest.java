package com.payverse.common.web.masking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "9876543210,MOBILE,******3210",
            "123456789012,ACCOUNT_NUMBER,********9012",
            "ABCDE1234F,ID,******234F",
            "ravi@example.com,EMAIL,r***@example.com",
            "secret,FULL,****",
            "123,MOBILE,***"
    })
    void masksByType(String input, MaskType type, String expected) {
        assertThat(PiiMasker.mask(input, type)).isEqualTo(expected);
    }

    @Test
    void nullAndEmptyAreUntouched() {
        assertThat(PiiMasker.mask(null, MaskType.MOBILE)).isNull();
        assertThat(PiiMasker.mask("", MaskType.MOBILE)).isEmpty();
        assertThat(PiiMasker.scrub(null)).isNull();
    }

    @Test
    void helperMethods() {
        assertThat(PiiMasker.maskMobile("9876543210")).isEqualTo("******3210");
        assertThat(PiiMasker.maskAccountNumber("000123456789")).isEqualTo("********6789");
        assertThat(PiiMasker.maskEmail("noatsign")).isEqualTo("******gn");
    }

    @Test
    void scrubsFreeText() {
        String scrubbed = PiiMasker.scrub("OTP for +91 9876543210 sent; acct 123456789012345 linked by ravi.kumar@example.com");
        assertThat(scrubbed)
                .doesNotContain("9876543210")
                .doesNotContain("123456789012345")
                .doesNotContain("ravi.kumar@")
                .contains("******3210")
                .contains("***********2345")
                .contains("r***@example.com");
    }

    @Test
    void scrubLeavesOrdinaryNumbersAlone() {
        assertThat(PiiMasker.scrub("Processed 42 events in 1500 ms, txn 2026")).isEqualTo("Processed 42 events in 1500 ms, txn 2026");
        assertThat(PiiMasker.scrub("id 6555555555")).contains("******5555"); // looks like a mobile: masked
    }

    record Profile(@Masked(MaskType.MOBILE) String mobile, @Masked(MaskType.ACCOUNT_NUMBER) String account, String name) {
    }

    @Test
    void maskedAnnotationAppliesDuringSerialization() {
        String json = JsonMapper.builder().build().writeValueAsString(new Profile("9876543210", "123456789012", "Ravi"));
        assertThat(json).isEqualTo("{\"mobile\":\"******3210\",\"account\":\"********9012\",\"name\":\"Ravi\"}");
    }
}
