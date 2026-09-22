package com.reconiq.normalization.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

/**
 * Guards BACKWARD compatibility (closes register gap G-B, the compatibility half): the schema currently
 * in schemas/canonical-transaction.avsc must still be able to read data written with the saved baseline
 * copy. If this test starts failing, either the change is a genuine break (revert it) or it is a
 * deliberate new version (update the baseline file and say so in the commit message).
 */
class SchemaCompatibilityTest {

    @Test
    void currentSchemaCanReadDataWrittenWithTheBaselineSchema() throws Exception {
        Schema baseline = new Schema.Parser()
                .parse(getClass().getClassLoader().getResourceAsStream("schema-baseline/canonical-transaction-v2-baseline.avsc"));
        Schema current = new Schema.Parser()
                .parse(new java.io.File("schemas/canonical-transaction.avsc"));

        SchemaCompatibility.SchemaPairCompatibility result =
                SchemaCompatibility.checkReaderWriterCompatibility(current, baseline);

        assertThat(result.getType())
                .as(result.getDescription())
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }
}
