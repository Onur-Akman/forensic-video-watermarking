package com.specter.embedder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        // Test sirasinda KeyManager'in HKDF turetimini calistirabilmesi icin dummy key.
        // Production'da SPECTER_WM_KEY env var'dan gelir.
        "specter.wm-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
})
class EmbedderApplicationTest {

    @Test
    void contextLoads() {
    }
}
