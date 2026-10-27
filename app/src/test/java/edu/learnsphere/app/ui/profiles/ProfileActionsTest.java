package edu.learnsphere.app.ui.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProfileActionsTest {

    @Test
    void backupNamesKeepOnlyCharactersEveryComputerAccepts() {
        assertThat(ProfileActions.safeFileName("Ada Lovelace")).isEqualTo("Ada Lovelace");
        assertThat(ProfileActions.safeFileName("A/B: C*?")).isEqualTo("A B C");
        assertThat(ProfileActions.safeFileName("  ")).isEqualTo("LearnSphere");
        assertThat(ProfileActions.safeFileName("x".repeat(80))).hasSize(60);
    }

    @Test
    void initialsComeFromTheName() {
        assertThat(AvatarPicker.initials("Ada Lovelace")).isEqualTo("AL");
        assertThat(AvatarPicker.COLOURS).hasSize(7);
    }
}
