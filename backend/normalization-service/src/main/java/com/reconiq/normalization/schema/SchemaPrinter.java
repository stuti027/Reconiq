package com.reconiq.normalization.schema;

import com.reconiq.canonical.CanonicalTransaction;

/**
 * Prints the generated CanonicalTransaction class's own schema, exactly as KafkaAvroSerializer sends it
 * for a registry lookup. Registering the schema any other way (for example pasting the .avsc file's own
 * text) can produce a schema that is semantically identical but not byte-identical -- codegen options
 * such as stringType add properties the raw file does not have -- which breaks lookup once
 * auto.register.schemas=false is set. Use scripts/register-schema.sh to register this output.
 */
public final class SchemaPrinter {

    private SchemaPrinter() {
    }

    public static void main(String[] args) {
        System.out.println(CanonicalTransaction.getClassSchema().toString());
    }
}
