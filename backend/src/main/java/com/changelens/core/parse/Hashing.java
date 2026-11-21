package com.changelens.core.parse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Short, stable content hashes used to tell "unchanged", "formatting only" and "really changed" apart. */
final class Hashing {

    private Hashing() {
    }

    static String sha(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8); // 64 bits is plenty to compare two versions of one method
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
