package com.specter.extraction.config;

import com.specter.extraction.util.KeyDerivationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Spring-managed provider for all cryptographic keys used in the extraction pipeline.
 *
 * Reads SPECTER_WM_KEY on application startup and derives the three sub-keys
 * via HKDF-SHA256 (contract §2.2). If the env var is missing or malformed,
 * the application fails to start — callers receive a 503 response.
 *
 * Contract §9: "If SPECTER_WM_KEY is missing, service responds with 503 KEY_UNAVAILABLE."
 *
 * Security rules (contract §2.1):
 * - The actual key must never appear in logs, error messages, or HTTP responses.
 * - Only dummy keys go in .env.example (never the real one).
 */
@Component
public class SpectreKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(SpectreKeyProvider.class);

    private byte[] masterKey;
    private byte[] authKey;
    private byte[] prngKey;
    private byte[] interleaverKey;

    private boolean keyAvailable = false;
    private String  keyError     = null;

    // ─────────────────────────────────────────────────────────────────
    // Startup key loading
    // ─────────────────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        try {
            log.info("Loading SPECTER_WM_KEY and deriving sub-keys via HKDF-SHA256...");
            masterKey       = KeyDerivationUtils.loadMasterKey();
            authKey         = KeyDerivationUtils.deriveAuthKey(masterKey);
            prngKey         = KeyDerivationUtils.derivePrngKey(masterKey);
            interleaverKey  = KeyDerivationUtils.deriveInterleaverKey(masterKey);
            keyAvailable    = true;
            // Log only that key loaded OK — never log the actual key bytes
            log.info("SPECTER_WM_KEY loaded successfully. Sub-keys derived (auth, prng, interleaver).");
        } catch (IllegalStateException e) {
            keyAvailable = false;
            keyError     = e.getMessage();
            // Log the error but NOT the key value
            log.error("Failed to load SPECTER_WM_KEY: {}", e.getMessage());
            log.error("Service will return 503 KEY_UNAVAILABLE for all extraction requests.");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Key accessors — throw if unavailable
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns whether the key was successfully loaded.
     * Controllers should call this before processing any request.
     */
    public boolean isKeyAvailable() {
        return keyAvailable;
    }

    /**
     * Human-readable error message if key failed to load (for 503 responses).
     * Never includes the actual key value.
     */
    public String getKeyError() {
        return keyError;
    }

    /**
     * 32-byte auth_key for HMAC auth tag generation/verification.
     * @throws IllegalStateException if SPECTER_WM_KEY was not loaded
     */
    public byte[] getAuthKey() {
        requireKey();
        return authKey;
    }

    /**
     * 32-byte prng_key for cell and pair selection PRNG.
     * @throws IllegalStateException if SPECTER_WM_KEY was not loaded
     */
    public byte[] getPrngKey() {
        requireKey();
        return prngKey;
    }

    /**
     * 32-byte interleave_key for bit permutation / deinterleaving.
     * @throws IllegalStateException if SPECTER_WM_KEY was not loaded
     */
    public byte[] getInterleaverKey() {
        requireKey();
        return interleaverKey;
    }

    private void requireKey() {
        if (!keyAvailable) {
            throw new IllegalStateException(
                    "SPECTER_WM_KEY is not available. Cannot perform extraction.");
        }
    }
}
