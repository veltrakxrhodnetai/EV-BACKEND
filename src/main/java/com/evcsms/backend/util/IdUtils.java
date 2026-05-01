package com.evcsms.backend.util;

import java.util.UUID;

public final class IdUtils {

    private IdUtils() {
    }

    public static String generateTransactionId() {
        return generatePrefixedId("txn");
    }

    public static String generatePrefixedId(String prefix) {
        String normalizedPrefix = (prefix == null || prefix.isBlank()) ? "id" : prefix.trim();
        return normalizedPrefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    public static UUID randomUuid() {
        return UUID.randomUUID();
    }

    public static UUID parseUuidOrNull(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawValue.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
