package dev.nuclr.plugin.core.assimp.blender;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Short content hashes used to name cache entries. */
final class Digests {

    /** Hex characters kept from the digest — 128 bits, ample for a local cache. */
    private static final int LENGTH = 32;

    private Digests() {
    }

    static String shortHash(String text) {
        return shortHash(text.getBytes(StandardCharsets.UTF_8));
    }

    static String shortHash(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
        byte[] hash = digest.digest(content);

        StringBuilder hex = new StringBuilder(LENGTH);
        for (int i = 0; i < hash.length && hex.length() < LENGTH; i++) {
            hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return hex.toString();
    }
}
