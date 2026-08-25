package com.accusharp.hrms.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Transparent AES-256-GCM encryption for the handful of employee columns that
 * are worth something to an attacker on their own: bank account number, IFSC,
 * UAN and ESIC IP number. Applied per-field via {@code @Convert}, so nothing
 * in the service or controller layers changes - values are plaintext in Java
 * and ciphertext in the database.
 *
 * <p><b>Why GCM.</b> Authenticated encryption: a row tampered with directly in
 * the database fails to decrypt rather than silently yielding a different
 * bank account number. A fresh 12-byte IV is generated per value, so the same
 * account number stored twice does not produce identical ciphertext - without
 * that, an attacker with read access could tell which employees share a bank
 * account, or confirm a guessed value by comparison.
 *
 * <p><b>Existing rows keep working.</b> Anything without the {@link #PREFIX}
 * marker is returned as-is on read, so a database written before this existed
 * is still readable and migrates lazily: each row becomes ciphertext the next
 * time it is saved. To convert everything at once, re-save the affected
 * employees (for example via the existing bulk salary-structure regeneration
 * path, or a one-off script) - there is no separate migration to run and no
 * window where reads break.
 *
 * <p><b>Column width.</b> Ciphertext is roughly {@code 4/3 * (12 + n + 16)}
 * characters plus the prefix, so the four converted columns were widened to
 * 255. That is a widening only, which {@code ddl-auto=update} applies safely.
 *
 * <p><b>Key management.</b> One key, supplied as base64-encoded 32 bytes in
 * {@code APP_ENCRYPTION_KEY}. The application refuses to start on the
 * committed placeholder, the same fail-closed contract {@code JwtService} and
 * {@code DataSeeder} already use - a silent fallback to a known key would be
 * worse than no encryption, because it would look encrypted. Rotating the key
 * requires decrypting with the old key and re-saving; there is no key-id in
 * the format yet, which is noted as a limitation rather than solved here.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /** Marks a value as ciphertext this class produced. Also the format version. */
    private static final String PREFIX = "enc1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String INSECURE_DEFAULT_KEY = "dev-only-insecure-encryption-key-change-me";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public EncryptedStringConverter(@Value("${app.security.encryption-key}") String base64Key) {
        if (INSECURE_DEFAULT_KEY.equals(base64Key)) {
            throw new IllegalStateException(
                    "app.security.encryption-key is still the placeholder value committed in "
                            + "application.properties. This key protects employee bank account, UAN and "
                            + "ESIC numbers at rest. Set APP_ENCRYPTION_KEY to 32 random bytes, base64 "
                            + "encoded - for example: openssl rand -base64 32 - before running this "
                            + "application anywhere holding real data. Refusing to start rather than "
                            + "writing data that only looks encrypted.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                    "app.security.encryption-key must be base64-encoded 32 bytes (openssl rand -base64 32)",
                    notBase64);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "app.security.encryption-key must decode to exactly 32 bytes for AES-256, got "
                            + decoded.length + " - generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Never fall back to writing plaintext - that would silently defeat
            // the whole point on a transient failure.
            throw new IllegalStateException("Failed to encrypt a sensitive field", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            // Written before this converter existed. Readable as-is; becomes
            // ciphertext the next time this row is saved.
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM authentication failure means the row was altered or the key is
            // wrong. Surfacing it is correct - returning garbage or null would
            // put a wrong bank account number onto a payslip.
            throw new IllegalStateException(
                    "Failed to decrypt a sensitive field - the value was tampered with, or "
                            + "APP_ENCRYPTION_KEY is not the key it was written with", e);
        }
    }
}
