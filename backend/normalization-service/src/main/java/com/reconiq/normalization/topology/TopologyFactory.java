package com.reconiq.normalization.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.dlq.DlqRecord;
import com.reconiq.normalization.input.RawEventParser;
import com.reconiq.normalization.mapping.MapperRegistry;
import java.util.List;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;

/** Builds the Topology from plain config: no broker connection needed, so it is fully unit-testable. */
public class TopologyFactory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> inputTopics;
    private final String canonicalTopic;
    private final String dlqTopic;
    private final RawEventParser parser;
    private final MapperRegistry mapperRegistry;
    private final Serde<CanonicalTransaction> canonicalSerde;

    public TopologyFactory(List<String> inputTopics, String canonicalTopic, String dlqTopic,
                           RawEventParser parser, MapperRegistry mapperRegistry, Serde<CanonicalTransaction> canonicalSerde) {
        this.inputTopics = inputTopics;
        this.canonicalTopic = canonicalTopic;
        this.dlqTopic = dlqTopic;
        this.parser = parser;
        this.mapperRegistry = mapperRegistry;
        this.canonicalSerde = canonicalSerde;
    }

    public Topology build() {
        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, String> raw = builder.stream(inputTopics, Consumed.with(Serdes.String(), Serdes.String()));

        KStream<String, Outcome> outcomes = raw.processValues(
                () -> new NormalizeProcessor(parser, mapperRegistry));

        outcomes.split(Named.as("route-"))
                .branch((key, value) -> value instanceof Outcome.Canonical,
                        Branched.withConsumer(canonicalStream -> canonicalStream
                                .map((key, value) -> {
                                    CanonicalTransaction transaction = ((Outcome.Canonical) value).transaction();
                                    return org.apache.kafka.streams.KeyValue.pair(transaction.getEventId(), transaction);
                                })
                                .to(canonicalTopic, Produced.with(Serdes.String(), canonicalSerde))))
                .branch((key, value) -> value instanceof Outcome.Rejected || value instanceof Outcome.Flagged,
                        Branched.withConsumer(dlqStream -> dlqStream
                                .mapValues(TopologyFactory::toJson)
                                .to(dlqTopic, Produced.with(Serdes.String(), Serdes.String()))))
                .noDefaultBranch();

        return builder.build();
    }

    private static String toJson(Outcome outcome) {
        DlqRecord record = outcome instanceof Outcome.Rejected r ? r.record() : ((Outcome.Flagged) outcome).record();
        try {
            return JSON.writeValueAsString(record);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize DLQ record to JSON", e);
        }
    }
}