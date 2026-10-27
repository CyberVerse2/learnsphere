package edu.learnsphere.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void theVersionComesFromTheBuild() {
        assertThat(AppInfo.version()).matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
