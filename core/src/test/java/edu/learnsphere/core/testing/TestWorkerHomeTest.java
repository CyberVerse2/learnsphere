package edu.learnsphere.core.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import edu.learnsphere.core.AppPaths;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TestWorkerHomeTest {

    @Test
    void eachGradleTestJvmHasAnAppHomeOfItsOwn() {
        String worker = System.getProperty(TestWorkerHome.WORKER_PROPERTY);
        assumeTrue(worker != null, "runs in a Gradle test worker");

        Path home = AppPaths.resolveHome(System.getProperties(), System.getenv());

        assertThat(home.getFileName()).hasToString("worker-" + worker);
        assertThat(home.getParent().getFileName()).hasToString("home");
    }
}
