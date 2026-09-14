package com.reconiq.reconciliation.audit;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRecordSignerTest {

    @Test
    void signsHashAndVerifiesWithTheSameKeyPair() {
        AuditRecordSigner signer = new AuditRecordSigner();
        String recordHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        String recordSignature = signer.signHash(recordHash);

        assertNotNull(recordSignature);
        assertFalse(recordSignature.isEmpty());
        assertTrue(signer.verify(recordHash, recordSignature));
    }

    @Test
    void verificationFailsWhenTheHashIsChanged() {
        AuditRecordSigner signer = new AuditRecordSigner();
        String recordHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String recordSignature = signer.signHash(recordHash);

        assertFalse(signer.verify("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", recordSignature));
    }

    @Test
    void verificationFailsWhenADifferentKeyPairIsUsed() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        AuditRecordSigner signer = new AuditRecordSigner(generator.generateKeyPair());
        AuditRecordSigner otherSigner = new AuditRecordSigner(generator.generateKeyPair());
        String recordHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String recordSignature = signer.signHash(recordHash);

        assertFalse(otherSigner.verify(recordHash, recordSignature));
    }
}
