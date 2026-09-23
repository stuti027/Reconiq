package com.reconiq.normalization.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.input.ParsedRawEvent;
import com.reconiq.normalization.rules.StatusMapping;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MappersTest {

    private static final String CSV = "source,raw_status,canonical_status\n"
            + "PAYMENT_GATEWAY,SUCCESS,COMPLETED\n"
            + "PAYMENT_GATEWAY,FAILED,FAILED\n"
            + "BANK,SETTLED,COMPLETED\n"
            + "BANK,FAILED,FAILED\n";

    private final StatusMapping statusMapping = StatusMapping.fromReader(new StringReader(CSV));

    private ParsedRawEvent event(String source, String status, BigDecimal amount) {
        return new ParsedRawEvent("evt1", "TXN1", source, Instant.parse("2026-08-20T09:48:00Z"),
                " ref-01 ", "MER1", "CUST1", "payment", "SET1", amount, " inr ", status, null);
    }

    @Test
    void gatewayMapperMapsKnownStatus() {
        MapResult result = new GatewayMapper(statusMapping).map(event("PAYMENT_GATEWAY", "SUCCESS", new BigDecimal("10.00")));
        CanonicalTransaction t = result.transaction();
        assertThat(result.statusUnmapped()).isFalse();
        assertThat(t.getSource()).isEqualTo("PAYMENT_GATEWAY");
        assertThat(t.getCanonicalStatus()).isEqualTo("COMPLETED");
        assertThat(t.getStatus()).isEqualTo("SUCCESS");
        assertThat(t.getReferenceKey()).isEqualTo("REF01");
        assertThat(t.getTransactionType()).isEqualTo("PAYMENT");
        assertThat(t.getAmountCurrency()).isEqualTo("INR");
    }

    @Test
    void bankMapperFlagsUnmappedStatusButStillWritesUnknown() {
        MapResult result = new BankMapper(statusMapping).map(event("BANK", "WEIRD_STATUS", null));
        assertThat(result.statusUnmapped()).isTrue();
        assertThat(result.transaction().getCanonicalStatus()).isEqualTo("UNKNOWN");
        assertThat(result.transaction().getAmountValue()).isNull();
    }

    @Test
    void registryFindsMapperIgnoringCase() {
        MapperRegistry registry = new MapperRegistry(List.of(new GatewayMapper(statusMapping), new BankMapper(statusMapping)));
        assertThat(registry.get(" bank ")).isPresent();
        assertThat(registry.get("payment_gateway")).isPresent();
        assertThat(registry.get("LEDGER")).isEmpty();
        assertThat(registry.get(null)).isEmpty();
    }
}