package edu.learnsphere.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.ModelChoice;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingKeysTest {

    @Test
    void keysAreUniqueAndDefaultsAreValid() {
        Set<String> keys = new HashSet<>();
        for (SettingKey<?> key : SettingKeys.ALL) {
            assertThat(keys.add(key.key())).as("duplicate key %s", key.key()).isTrue();
            assertThat(roundTrip(key)).as(key.key()).isEqualTo(key.defaultValue());
        }
    }

    private static <T> T roundTrip(SettingKey<T> key) {
        assertThat(key.isValid(key.defaultValue())).isTrue();
        return key.parse(key.format(key.defaultValue()));
    }

    @Test
    void parsesStoredValuesAndFallsBackOnBadOnes() {
        assertThat(SettingKeys.SESSION_SIZE.parse("30")).isEqualTo(30);
        assertThat(SettingKeys.SESSION_SIZE.parse("3")).isEqualTo(20);
        assertThat(SettingKeys.SESSION_SIZE.parse("lots")).isEqualTo(20);
        assertThat(SettingKeys.DESIRED_RETENTION.parse("0.99")).isEqualTo(0.90);
        assertThat(SettingKeys.MODEL_CHOICE.parse("chat-9b")).isEqualTo(ModelChoice.CHAT_9B);
        assertThat(SettingKeys.NEW_ITEMS_PER_DAY.parse(null)).isEqualTo(15);
        assertThat(SettingKeys.DESCRIBE_FIGURES.parse("false")).isFalse();
    }

    @Test
    void defaultsMatchAppSettings() {
        AppSettings defaults = AppSettings.defaults();
        assertThat(defaults.newItemsPerDay()).isEqualTo(SettingKeys.NEW_ITEMS_PER_DAY.defaultValue());
        assertThat(defaults.desiredRetention()).isEqualTo(SettingKeys.DESIRED_RETENTION.defaultValue());
        assertThat(defaults.sessionSize()).isEqualTo(SettingKeys.SESSION_SIZE.defaultValue());
        assertThat(defaults.modelChoice()).isEqualTo(SettingKeys.MODEL_CHOICE.defaultValue());
        assertThat(defaults.evidenceThreshold()).isEqualTo(SettingKeys.EVIDENCE_THRESHOLD.defaultValue());
        assertThat(defaults.describeFigures()).isTrue();
    }
}
