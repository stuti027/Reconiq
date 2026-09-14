package com.reconiq.reconciliation.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class AuditRecordHasher {

    private AuditRecordHasher() {
    }

    public static String createHash(AuditRecord record) {
        String payload = fieldValue(record.getOccurredAt())
                + "|"
                + fieldValue(record.getEventType())
                + "|"
                + fieldValue(record.getActor())
                + "|"
                + fieldValue(record.getAction())
                + "|"
                + fieldValue(record.getSource())
                + "|"
                + fieldValue(record.getDetails());
        return sha256Hex(payload);
    }

    private static String fieldValue(Object field) {
        if (field == null) {
            return "";
        }
        return field.toString();
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < hashedBytes.length; i++) {
                String part = Integer.toHexString(0xff & hashedBytes[i]);
                if (part.length() == 1) {
                    hex.append('0');
                }
                hex.append(part);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
