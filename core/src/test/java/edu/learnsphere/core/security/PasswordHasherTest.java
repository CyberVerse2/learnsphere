package edu.learnsphere.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher fast = new PasswordHasher(1_000);

    @Test
    void verifiesTheRightPasswordOnly() {
        String encoded = fast.hash("correct horse".toCharArray());

        assertThat(encoded).startsWith("pbkdf2-sha256$1000$");
        assertThat(encoded.split("\\$")).hasSize(4);
        assertThat(fast.verify("correct horse".toCharArray(), encoded)).isTrue();
        assertThat(fast.verify("correct house".toCharArray(), encoded)).isFalse();
        assertThat(fast.verify("".toCharArray(), encoded)).isFalse();
    }

    @Test
    void saltsEveryHash() {
        String a = fast.hash("same".toCharArray());
        String b = fast.hash("same".toCharArray());
        assertThat(a).isNotEqualTo(b);
        assertThat(fast.verify("same".toCharArray(), b)).isTrue();
    }

    @Test
    void rejectsMalformedHashesWithoutThrowing() {
        assertThat(fast.verify("x".toCharArray(), null)).isFalse();
        assertThat(fast.verify("x".toCharArray(), "")).isFalse();
        assertThat(fast.verify("x".toCharArray(), "md5$abc")).isFalse();
        assertThat(fast.verify("x".toCharArray(), "pbkdf2-sha256$notanumber$abc$def")).isFalse();
        assertThat(fast.verify("x".toCharArray(), "pbkdf2-sha256$1000$!!!$???")).isFalse();
    }

    @Test
    void verifiesHashesMadeWithOtherIterationCounts() {
        String older = new PasswordHasher(500).hash("pw".toCharArray());
        assertThat(fast.verify("pw".toCharArray(), older)).isTrue();
        assertThat(fast.needsRehash(older)).isTrue();
        assertThat(fast.needsRehash(fast.hash("pw".toCharArray()))).isFalse();
    }

    @Test
    void usesTheRecommendedIterationCountByDefault() {
        PasswordHasher standard = new PasswordHasher();
        String encoded = standard.hash("pw".toCharArray());
        assertThat(encoded).startsWith("pbkdf2-sha256$310000$");
        assertThat(standard.verify("pw".toCharArray(), encoded)).isTrue();
    }

    @Test
    void matchesAKnownPbkdf2Vector() {
        // RFC 7914 section 11: PBKDF2-HMAC-SHA256, P="passwd", S="salt", c=1, dkLen=64 (first 16 bytes).
        byte[] key = PasswordHasher.deriveKey("passwd".toCharArray(), "salt".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, 512);
        assertThat(java.util.HexFormat.of().formatHex(key, 0, 16)).isEqualTo("55ac046e56e3089fec1691c22544b605");
    }

    @Test
    void wipesPasswords() {
        char[] password = "secret".toCharArray();
        PasswordHasher.wipe(password);
        assertThat(password).containsOnly('\0');
        PasswordHasher.wipe(null);
    }

    @Test
    void refusesTooFewIterations() {
        assertThatThrownBy(() -> new PasswordHasher(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
