package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackageCryptoTest {

    private static final int SEGMENT = PackageCrypto.SEGMENT_SIZE;
    private static final int SEALED = SEGMENT + PackageCrypto.TAG_BYTES;
    private static final byte[] AAD = PackageCrypto.entryAad("package", 3, "course.json");

    private static PackageKey key;
    private static PackageKey otherKey;

    @BeforeAll
    static void deriveKeys() {
        key = PackageKey.create("orchid".toCharArray());
        otherKey = PackageKey.create("orchid".toCharArray());
    }

    @AfterAll
    static void wipeKeys() {
        key.close();
        otherKey.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, SEGMENT - 1, SEGMENT, SEGMENT + 1, 3 * SEGMENT, 3 * SEGMENT + 17})
    void roundTripsEverySize(int size) throws IOException {
        byte[] plain = random(size);

        byte[] sealed = encrypt(plain, key, AAD);

        int segments = Math.max(1, (size + SEGMENT - 1) / SEGMENT);
        assertThat(sealed).hasSize(PackageCrypto.PREFIX_BYTES + size + segments * PackageCrypto.TAG_BYTES);
        assertThat(decrypt(sealed, key, AAD)).isEqualTo(plain);
    }

    @Test
    void sameContentEncryptsDifferentlyEachTime() throws IOException {
        byte[] plain = random(1000);
        assertThat(encrypt(plain, key, AAD)).isNotEqualTo(encrypt(plain, key, AAD));
    }

    @Test
    void wrongKeyOrOtherEntryFailsToDecrypt() throws IOException {
        byte[] sealed = encrypt(random(5000), key, AAD);

        assertDamaged(sealed, otherKey, AAD);
        assertDamaged(sealed, key, PackageCrypto.entryAad("package", 3, "pages.json"));
        assertDamaged(sealed, key, PackageCrypto.entryAad("package", 4, "course.json"));
    }

    @Test
    void droppedFinalSegmentIsDetected() throws IOException {
        byte[] sealed = encrypt(random(3 * SEGMENT + 100), key, AAD);
        // Cut exactly at a segment boundary: what remains looks complete but its last segment is not final.
        byte[] cut = Arrays.copyOf(sealed, PackageCrypto.PREFIX_BYTES + 3 * SEALED);

        assertDamaged(cut, key, AAD);
    }

    @Test
    void cutInsideASegmentIsDetected() throws IOException {
        byte[] sealed = encrypt(random(2 * SEGMENT), key, AAD);

        assertDamaged(Arrays.copyOf(sealed, sealed.length - 5), key, AAD);
        assertDamaged(Arrays.copyOf(sealed, 3), key, AAD);
    }

    @Test
    void reorderedSegmentsAreDetected() throws IOException {
        byte[] sealed = encrypt(random(3 * SEGMENT), key, AAD);
        byte[] swapped = sealed.clone();
        int first = PackageCrypto.PREFIX_BYTES;
        System.arraycopy(sealed, first + SEALED, swapped, first, SEALED);
        System.arraycopy(sealed, first, swapped, first + SEALED, SEALED);

        assertDamaged(swapped, key, AAD);
    }

    @Test
    void flippedBitIsDetected() throws IOException {
        byte[] sealed = encrypt(random(10_000), key, AAD);
        sealed[PackageCrypto.PREFIX_BYTES + 42] ^= 0x10;

        assertDamaged(sealed, key, AAD);
    }

    @Test
    void keyCheckTellsAWrongCodeFromDamage() {
        String check = PackageCrypto.keyCheck(key);

        assertThat(PackageCrypto.isWellFormedKeyCheck(check)).isTrue();
        assertThat(PackageCrypto.verifyKeyCheck(key, check)).isTrue();
        assertThat(PackageCrypto.verifyKeyCheck(otherKey, check)).isFalse();
        assertThat(PackageCrypto.isWellFormedKeyCheck("not base64!")).isFalse();
    }

    @Test
    void codesAreTrimmedAndMustBeLongEnough() {
        assertThat(PackageKey.isUsableCode("orchid".toCharArray())).isTrue();
        assertThat(PackageKey.isUsableCode("  ab  ".toCharArray())).isFalse();
        assertThat(PackageKey.isUsableCode(null)).isFalse();
        try (PackageKey padded = PackageKey.derive("  orchid\n".toCharArray(), key.salt(), key.iterations())) {
            assertThat(PackageCrypto.verifyKeyCheck(padded, PackageCrypto.keyCheck(key))).isTrue();
        }
    }

    private static void assertDamaged(byte[] sealed, PackageKey k, byte[] aad) {
        assertThatThrownBy(() -> decrypt(sealed, k, aad)).isInstanceOf(DamagedPackageException.class);
    }

    private static byte[] encrypt(byte[] plain, PackageKey k, byte[] aad) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream encrypting = PackageCrypto.encrypt(out, k, aad)) {
            // Uneven writes cross segment boundaries.
            int offset = 0;
            int step = 1;
            while (offset < plain.length) {
                int n = Math.min(step, plain.length - offset);
                encrypting.write(plain, offset, n);
                offset += n;
                step = step * 3 + 1;
            }
        }
        return out.toByteArray();
    }

    private static byte[] decrypt(byte[] sealed, PackageKey k, byte[] aad) throws IOException {
        try (InputStream in = PackageCrypto.decrypt(new ByteArrayInputStream(sealed), k, aad)) {
            return in.readAllBytes();
        }
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }
}
