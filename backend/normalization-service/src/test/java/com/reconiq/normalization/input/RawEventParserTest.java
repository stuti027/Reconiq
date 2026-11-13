package com.reconiq.normalization.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RawEventParserTest {

    private final RawEventParser parser = new RawEventParser(Set.of("BANK", "PAYMENT_GATEWAY"));

    private static final String VALID = """
            {"event_id":"evt_gw_00006","event_type":"transaction.created",
             "event_timestamp":1787219280.000000000,"source":"PAYMENT_GATEWAY",
             "data":{"transaction":{"transaction_id":"TXN00006","reference_id":"REF00006",
               "merchant_id":"MER001","customer_id":"CUST1001","transaction_type":"PAYMENT",
               "settlement_id":"SET00006","amount":{"value":1051.3,"currency":"INR"},
               "timestamp":1787219280.000000000,"status":"SUCCESS","description":null}}}
            """;

    /** Replaces text in the valid message; fails if the text is not there, so a typo cannot hide. */
    private static String mutate(String from, String to) {
        assertThat(VALID).contains(from);
        return VALID.replace(from, to);
    }

    private ParsedRawEvent parsed(String text) {
        ParseResult result = parser.parse(text);
        assertThat(result).isInstanceOf(ParsedRawEvent.class);
        return (ParsedRawEvent) result;
    }

    private void assertRejected(String text, RejectReason expected) {
        ParseResult result = parser.parse(text);
        assertThat(result).isInstanceOf(Rejection.class);
        assertThat(((Rejection) result).reason()).isEqualTo(expected);
    }

    @Test
    void parsesValidWireFormatMessage() {
        ParsedRawEvent event = parsed(VALID);
        assertThat(event.eventId()).isEqualTo("evt_gw_00006");
        assertThat(event.transactionId()).isEqualTo("TXN00006");
        assertThat(event.source()).isEqualTo("PAYMENT_GATEWAY");
        assertThat(event.eventTimestamp()).isEqualTo(Instant.parse("2026-08-20T09:48:00Z"));
        assertThat(event.referenceId()).isEqualTo("REF00006");
        assertThat(event.merchantId()).isEqualTo("MER001");
        assertThat(event.customerId()).isEqualTo("CUST1001");
        assertThat(event.transactionType()).isEqualTo("PAYMENT");
        assertThat(event.settlementId()).isEqualTo("SET00006");
        assertThat(event.amountValue()).isEqualTo(new BigDecimal("1051.30"));
        assertThat(event.amountCurrency()).isEqualTo("INR");
        assertThat(event.status()).isEqualTo("SUCCESS");
        assertThat(event.description()).isNull();
    }

    @Test
    void parsesIsoTimestamp() {
        String text = mutate("\"event_timestamp\":1787219280.000000000",
                "\"event_timestamp\":\"2026-08-20T09:48:00Z\"");
        assertThat(parsed(text).eventTimestamp()).isEqualTo(Instant.parse("2026-08-20T09:48:00Z"));
    }

    @Test
    void acceptsSourceIgnoringCaseAndSpaces() {
        assertThat(parsed(mutate("\"PAYMENT_GATEWAY\"", "\"  payment_gateway \"")).source()).isNotNull();
    }

    @Test
    void keepsMissingAmountAsNull() {
        String text = mutate("\"amount\":{\"value\":1051.3,\"currency\":\"INR\"}", "\"amount\":null");
        ParsedRawEvent event = parsed(text);
        assertThat(event.amountValue()).isNull();
        assertThat(event.amountCurrency()).isNull();
    }

    @Test
    void keepsMissingOptionalFieldsAsNull() {
        ParsedRawEvent event = parsed(mutate("\"reference_id\":\"REF00006\",", ""));
        assertThat(event.referenceId()).isNull();
    }

    @Test
    void rejectsInvalidJson() {
        for (String text : new String[] {null, "", "   ", "not json", "[1,2]", "42", "{\"a\":"}) {
            assertRejected(text, RejectReason.INVALID_JSON);
        }
    }

    @Test
    void rejectsMissingOrBlankEventId() {
        assertRejected(mutate("\"event_id\":\"evt_gw_00006\",", ""), RejectReason.MISSING_EVENT_ID);
        assertRejected(mutate("\"evt_gw_00006\"", "\"  \""), RejectReason.MISSING_EVENT_ID);
    }

    @Test
    void rejectsMissingOrInvalidEventTimestamp() {
        assertRejected(mutate("\"event_timestamp\":1787219280.000000000,", ""), RejectReason.INVALID_TIMESTAMP);
        assertRejected(mutate("\"event_timestamp\":1787219280.000000000", "\"event_timestamp\":12345"),
                RejectReason.INVALID_TIMESTAMP);
        assertRejected(mutate("\"event_timestamp\":1787219280.000000000", "\"event_timestamp\":\"yesterday\""),
                RejectReason.INVALID_TIMESTAMP);
    }

    @Test
    void rejectsUnknownOrMissingSource() {
        assertRejected(mutate("\"PAYMENT_GATEWAY\"", "\"CARRIER_PIGEON\""), RejectReason.UNKNOWN_SOURCE);
        assertRejected(mutate("\"source\":\"PAYMENT_GATEWAY\",", ""), RejectReason.UNKNOWN_SOURCE);
    }

    @Test
    void rejectsMissingTransaction() {
        assertRejected("{\"event_id\":\"e1\",\"event_timestamp\":1787219280,\"source\":\"BANK\",\"data\":null}",
                RejectReason.MISSING_TRANSACTION);
        assertRejected("{\"event_id\":\"e1\",\"event_timestamp\":1787219280,\"source\":\"BANK\",\"data\":{}}",
                RejectReason.MISSING_TRANSACTION);
        assertRejected("{\"event_id\":\"e1\",\"event_timestamp\":1787219280,\"source\":\"BANK\"}",
                RejectReason.MISSING_TRANSACTION);
    }

    @Test
    void rejectsMissingOrBlankTransactionId() {
        assertRejected(mutate("\"transaction_id\":\"TXN00006\",", ""), RejectReason.MISSING_TRANSACTION_ID);
        assertRejected(mutate("\"TXN00006\"", "\" \""), RejectReason.MISSING_TRANSACTION_ID);
    }

    @Test
    void rejectsInvalidAmounts() {
        assertRejected(mutate("\"value\":1051.3", "\"value\":\"abc\""), RejectReason.INVALID_AMOUNT);
        assertRejected(mutate("\"value\":1051.3", "\"value\":1051.305"), RejectReason.INVALID_AMOUNT_SCALE);
    }

    @Test
    void rejectionKeepsTheOriginalText() {
        ParseResult result = parser.parse("not json");
        Rejection rejection = (Rejection) result;
        assertThat(rejection.originalText()).isEqualTo("not json");
        assertThat(rejection.detail()).isNotBlank();
    }

    @Test
    void parsesEverySampleEvent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode root = mapper.readTree(Path.of("../../sample-data-file/sample_data.json").toFile());

        int total = 0;
        int gateway = 0;
        int bank = 0;
        int withoutAmount = 0;
        Set<String> eventIds = new HashSet<>();
        for (JsonNode event : root.get("events")) {
            total++;
            ParseResult result = parser.parse(event.toString());
            assertThat(result).as("event %s", event.get("event_id")).isInstanceOf(ParsedRawEvent.class);
            ParsedRawEvent parsed = (ParsedRawEvent) result;
            eventIds.add(parsed.eventId());
            if ("PAYMENT_GATEWAY".equals(parsed.source())) {
                gateway++;
            } else if ("BANK".equals(parsed.source())) {
                bank++;
            }
            if (parsed.amountValue() == null) {
                withoutAmount++;
            }
        }
        assertThat(total).isEqualTo(197);
        assertThat(gateway).isEqualTo(106);
        assertThat(bank).isEqualTo(91);
        assertThat(withoutAmount).isEqualTo(1);
        assertThat(eventIds).hasSize(197);
    }
}