package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PendingKeysTest {

    private static byte[] salt;
    private static int iterations;

    @BeforeAll
    static void salt() {
        try (PackageKey key = PackageKey.create("orchid".toCharArray())) {
            salt = key.salt();
            iterations = key.iterations();
        }
    }

    @Test
    void keyIsTakenOnce() {
        try (PendingKeys keys = new PendingKeys()) {
            PackageKey key = key();
            String ref = keys.put(key);

            assertThat(keys.take(ref)).isSameAs(key);
            assertThat(keys.take(ref)).isNull();
            assertThat(keys.take(null)).isNull();
            assertThat(keys.size()).isZero();
        }
    }

    @Test
    void keysOfJobsThatEndedAreWiped() {
        try (PendingKeys keys = new PendingKeys()) {
            PackageKey cancelled = key();
            PackageKey waiting = key();
            PackageKey notYetQueued = key();
            String cancelledRef = keys.put(cancelled);
            keys.attach(cancelledRef, "job-1");
            keys.attach(keys.put(waiting), "job-2");
            keys.put(notYetQueued);

            keys.purge(Set.of("job-2")::contains);

            assertThat(keys.size()).isEqualTo(2);
            assertThat(keys.take(cancelledRef)).isNull();
            assertThatThrownBy(cancelled::secretKey).isInstanceOf(IllegalStateException.class);
            assertThat(waiting.secretKey()).isNotNull();
        }
    }

    @Test
    void closingWipesEveryKey() {
        PackageKey key = key();
        PendingKeys keys = new PendingKeys();
        keys.put(key);

        keys.close();

        assertThat(keys.size()).isZero();
        assertThatThrownBy(key::secretKey).isInstanceOf(IllegalStateException.class);
    }

    private static PackageKey key() {
        return PackageKey.derive("orchid".toCharArray(), salt, iterations);
    }
}
