package com.reconiq.normalization.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.input.ParseResult;
import com.reconiq.normalization.input.ParsedRawEvent;
import com.reconiq.normalization.input.RawEventParser;
import com.reconiq.normalization.mapping.BankMapper;
import com.reconiq.normalization.mapping.GatewayMapper;
import com.reconiq.normalization.mapping.MapResult;
import com.reconiq.normalization.mapping.MapperRegistry;
import com.reconiq.normalization.rules.StatusMapping;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.Test;

/**
 * Every row the mappers produce must encode with a plain Avro writer (closes register gap G-B: the mappers
 * must never build a record that satisfies the generated class but cannot actually be serialized). No
 * Confluent serde and no Schema Registry involved here -- this is Avro's own encoding, in isolation.
 */
class PlainAvroEncodeTest {

    private static final String STATUS_CSV = "source,raw_status,canonical_status\n"
            + "PAYMENT_GATEWAY,SUCCESS,COMPLETED\n"
            + "PAYMENT_GATEWAY,FAILED,FAILED\n"
            + "BANK,SETTLED,COMPLETED\n"
            + "BANK,FAILED,FAILED\n";

    @Test
    void everySampleEventMapsToARecordThatEncodesWithAPlainDatumWriter() throws Exception {
        StatusMapping statusMapping = StatusMapping.fromReader(new StringReader(STATUS_CSV));
        RawEventParser parser = new RawEventParser(java.util.Set.of("BANK", "PAYMENT_GATEWAY"));
        MapperRegistry mapperRegistry = new MapperRegistry(
                List.of(new GatewayMapper(statusMapping), new BankMapper(statusMapping)));
        SpecificDatumWriter<CanonicalTransaction> writer = new SpecificDatumWriter<>(CanonicalTransaction.class);

        ObjectMapper mapper = new ObjectMapper();
        int encoded = 0;
        for (String line : readFixtureLines("wire-events.jsonl")) {
            JsonNode event = mapper.readTree(line);
            ParseResult result = parser.parse(line);
            assertThat(result).as("event %s", event.get("event_id")).isInstanceOf(ParsedRawEvent.class);
            ParsedRawEvent parsed = (ParsedRawEvent) result;

            MapResult mapped = mapperRegistry.get(parsed.source()).orElseThrow().map(parsed);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(mapped.transaction(), encoder);
            encoder.flush();

            assertThat(out.size()).as("event %s produced no bytes", event.get("event_id")).isGreaterThan(0);
            encoded++;
        }
        assertThat(encoded).isEqualTo(197);
    }

    private List<String> readFixtureLines(String resourceName) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture not found: " + resourceName);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }
}
