package com.specter.embedder.service;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.config.EmbedderProperties;
import com.specter.embedder.core.crypto.Hkdf;
import com.specter.embedder.exception.EmbedException;
import com.specter.embedder.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HexFormat;

/**
 * SPECTER_WM_KEY master anahtarini yukler ve HKDF-SHA256 ile alt anahtarlari uretir
 * (contract section 2.2). Anahtar bulunamazsa istek aninda 503 KEY_UNAVAILABLE atilir;
 * uygulama bu durumda baslatilirken patlamaz.
 */
@Component
public class KeyManager {

    private static final Logger log = LoggerFactory.getLogger(KeyManager.class);

    private final EmbedderProperties props;

    private byte[] authKey;
    private byte[] prngKey;
    private byte[] interleaveKey;
    private boolean ready;

    public KeyManager(EmbedderProperties props) {
        this.props = props;
        try {
            initialize();
        } catch (EmbedException e) {
            log.warn("KeyManager not initialized at startup: {}", e.getMessage());
            this.ready = false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public byte[] authKey() {
        ensureReady();
        return authKey.clone();
    }

    public byte[] prngKey() {
        ensureReady();
        return prngKey.clone();
    }

    public byte[] interleaveKey() {
        ensureReady();
        return interleaveKey.clone();
    }

    private synchronized void ensureReady() {
        if (ready) {
            return;
        }
        initialize();
    }

    private void initialize() {
        String hex = props.wmKey();
        if (hex == null || hex.isBlank()) {
            throw new EmbedException(ErrorCode.KEY_UNAVAILABLE,
                    ContractConstants.KEY_ENV + " is not set");
        }
        if (hex.length() != ContractConstants.KEY_HEX_LENGTH) {
            throw new EmbedException(ErrorCode.KEY_UNAVAILABLE,
                    ContractConstants.KEY_ENV + " must be " + ContractConstants.KEY_HEX_LENGTH + " hex chars");
        }
        byte[] master;
        try {
            master = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new EmbedException(ErrorCode.KEY_UNAVAILABLE,
                    ContractConstants.KEY_ENV + " is not valid hex");
        }
        if (master.length != ContractConstants.KEY_BYTES) {
            throw new EmbedException(ErrorCode.KEY_UNAVAILABLE,
                    ContractConstants.KEY_ENV + " must decode to " + ContractConstants.KEY_BYTES + " bytes");
        }
        this.authKey = Hkdf.deriveKey(master, ContractConstants.HKDF_SALT,
                ContractConstants.HKDF_INFO_AUTH, ContractConstants.KEY_BYTES);
        this.prngKey = Hkdf.deriveKey(master, ContractConstants.HKDF_SALT,
                ContractConstants.HKDF_INFO_PRNG, ContractConstants.KEY_BYTES);
        this.interleaveKey = Hkdf.deriveKey(master, ContractConstants.HKDF_SALT,
                ContractConstants.HKDF_INFO_INTERLEAVER, ContractConstants.KEY_BYTES);
        this.ready = true;
        log.info("KeyManager initialized; HKDF subkeys derived");
    }
}
