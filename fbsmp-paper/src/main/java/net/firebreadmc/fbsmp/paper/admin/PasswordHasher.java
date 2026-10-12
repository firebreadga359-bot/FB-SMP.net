package net.firebreadmc.fbsmp.paper.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hashing for dashboard operator accounts.
 * Hashes and salts are Base64-encoded; verification is constant-time.
 */
final class PasswordHasher {
    static final int DEFAULT_ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    static String hash(String password, String salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    Base64.getDecoder().decode(salt), iterations, KEY_LENGTH_BITS);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (Exception exception) {
            throw new IllegalStateException("Password hashing failed.", exception);
        }
    }

    static boolean verify(String password, String salt, int iterations, String expectedHash) {
        String actual = hash(password, salt, iterations);
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
