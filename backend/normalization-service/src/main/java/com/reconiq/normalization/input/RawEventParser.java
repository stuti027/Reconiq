package com.reconiq.normalization.input;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Parses one input message (JSON text) into a validated event, or rejects it with a reason code.
 * Bad input never throws: it always produces a Rejection.
 */
public class RawEventParser {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Set<String> knownSources;

    public RawEventParser(Set<String> knownSources) {
        this.knownSources = Set.copyOf(knownSources);
    }

    public ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            return reject(RejectReason.INVALID_JSON, "message is empty", text);
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            return reject(RejectReason.INVALID_JSON, "message is not valid JSON", text);
        }
        if (root == null || !root.isObject()) {
            return reject(RejectReason.INVALID_JSON, "message is not a JSON object", text);
        }

        String eventId = text(root, "event_id");
        if (isBlank(eventId)) {
            return reject(RejectReason.MISSING_EVENT_ID, "event_id is missing or blank", text);
        }

        Optional<Instant> timestamp = TimestampParser.parse(root.get("event_timestamp"));
        if (timestamp.isEmpty()) {
            return reject(RejectReason.INVALID_TIMESTAMP, "event_timestamp is missing, unparseable or out of range", text);
        }

        String source = text(root, "source");
        if (isBlank(source) || !knownSources.contains(source.trim().toUpperCase(Locale.ROOT))) {
            return reject(RejectReason.UNKNOWN_SOURCE, "source is missing or not a known source", text);
        }

        JsonNode transaction = root.path("data").path("transaction");
        if (!transaction.isObject()) {
            return reject(RejectReason.MISSING_TRANSACTION, "data.transaction is missing or null", text);
        }

        String transactionId = text(transaction, "transaction_id");
        if (isBlank(transactionId)) {
            return reject(RejectReason.MISSING_TRANSACTION_ID, "transaction_id is missing or blank", text);
        }

        JsonNode amount = transaction.path("amount");
        AmountResult amountResult = AmountParser.parse(amount.path("value"));
        if (!amountResult.isValid()) {
            return reject(amountResult.error(), "amount.value is invalid", text);
        }

        return new ParsedRawEvent(
                eventId,
                transactionId,
                source,
                timestamp.get(),
                text(transaction, "reference_id"),
                text(transaction, "merchant_id"),
                text(transaction, "customer_id"),
                text(transaction, "transaction_type"),
                text(transaction, "settlement_id"),
                amountResult.value(),
                text(amount, "currency"),
                text(transaction, "status"),
                text(transaction, "description"));
    }

    private static Rejection reject(RejectReason reason, String detail, String originalText) {
        return new Rejection(reason, detail, originalText);
    }

    /** Returns the field as text, or null if it is missing, null, or not a simple value. */
    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || value.isMissingNode() || !value.isValueNode()) {
            return null;
        }
        return value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}