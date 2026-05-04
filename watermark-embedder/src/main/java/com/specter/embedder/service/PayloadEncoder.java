package com.specter.embedder.service;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.core.codec.BitPacker;
import com.specter.embedder.core.codec.Hamming74;
import com.specter.embedder.core.codec.Interleaver;
import com.specter.embedder.core.crypto.AuthTag;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * 32-bit watermark id'den 84-bit interleaved codeword uretir (contract section 1).
 *
 *   id (32 bit big-endian)
 *      || HMAC-SHA256(auth_key, id)[0:16 bit]    -> raw_packet (48 bit)
 *      -> Hamming(7,4) per nibble                -> codeword   (84 bit)
 *      -> PRNG-permute (interleave_key)          -> interleaved codeword (84 bit)
 */
@Component
public class PayloadEncoder {

    private final KeyManager keyManager;

    public PayloadEncoder(KeyManager keyManager) {
        this.keyManager = keyManager;
    }

    public byte[] encode(long watermarkId) {
        byte[] idBytes = ByteBuffer.allocate(4).putInt((int) watermarkId).array();
        byte[] tagBytes = AuthTag.compute(keyManager.authKey(), watermarkId);
        byte[] rawPacketBytes = BitPacker.concat(idBytes, tagBytes);
        byte[] rawPacketBits = BitPacker.bytesToBits(rawPacketBytes, ContractConstants.RAW_PACKET_BITS);
        byte[] codewordBits = Hamming74.encodePacket(rawPacketBits);
        Interleaver interleaver = new Interleaver(keyManager.interleaveKey());
        return interleaver.interleave(codewordBits);
    }
}
