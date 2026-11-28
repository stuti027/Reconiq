package com.reconiq.reporting.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Checks the JSON shapes of contract sections 1.1-1.5 with Spring Boot's own ObjectMapper. */
@JsonTest
class ContractJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void moneyIsAStringWithTwoDecimals() throws Exception {
        assertThat(mapper.writeValueAsString(Money.of(new BigDecimal("100"), "USD")))
                .isEqualTo("{\"value\":\"100.00\",\"currency\":\"USD\"}");
    }

    @Test
    void missingAmountIsNull() {
        assertThat(Money.of(null, "INR")).isNull();
    }

    @Test
    void availableValueIsThePlainValue() throws Exception {
        assertThat(mapper.writeValueAsString(Map.of("matchRate", Available.of(0.82))))
                .isEqualTo("{\"matchRate\":0.82}");
    }

    @Test
    void unavailableValueIsTheReasonObjectNeverZero() throws Exception {
        Available<Double> rate = Available.unavailable(UnavailableReason.PHASE3_RESULTS_NOT_STORED);
        assertThat(mapper.writeValueAsString(Map.of("matchRate", rate)))
                .isEqualTo("{\"matchRate\":{\"available\":false,\"reason\":\"PHASE3_RESULTS_NOT_STORED\"}}");
    }

    @Test
    void availableRefusesNull() {
        assertThatThrownBy(() -> Available.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void instantIsIsoUtcText() throws Exception {
        assertThat(mapper.writeValueAsString(Instant.parse("2026-08-23T05:00:00Z")))
                .isEqualTo("\"2026-08-23T05:00:00Z\"");
    }

    @Test
    void pageResultHasItemsPageSizeTotal() throws Exception {
        assertThat(mapper.writeValueAsString(new PageResult<>(List.of("a"), 0, 25, 197)))
                .isEqualTo("{\"items\":[\"a\"],\"page\":0,\"size\":25,\"total\":197}");
    }

    @Test
    void pageParamsDefaultsAndLimits() {
        assertThat(PageParams.of(null, null)).isEqualTo(new PageParams(0, 25));
        assertThat(PageParams.of(2, 25).offset()).isEqualTo(50);
        assertThatThrownBy(() -> PageParams.of(0, 101)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PageParams.of(-1, 25)).isInstanceOf(BadRequestException.class);
    }
}
