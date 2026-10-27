package edu.learnsphere.app.dev;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.Workspace;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

/** The demo profile's progress, through the service interface the Progress screen uses. */
class DemoProgressTest {

    @TempDir
    Path home;

    private AppContext context;
    private Workspace workspace;

    @BeforeAll
    static void toolkit() throws TimeoutException {
        // Opening a profile publishes it on the FX thread.
        FxToolkit.registerPrimaryStage();
    }

    @BeforeEach
    void open() {
        context = new AppContext(AppPaths.at(home), Clock.systemDefaultZone(), true, DemoWorkspace.instantFactory());
        DemoData.seedProfiles(context);
        workspace = context.openLastProfile().orElseThrow();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void itemStatisticsCoverApprovedQuestionsOnlyHardestFirst() {
        String course = DemoData.ORGANIC_CHEMISTRY;
        assertThat(workspace.items().counts(course).draft()).as("the demo course has drafts to leave out").isPositive();

        List<ItemStats> stats = workspace.progress().itemStats(course);

        assertThat(stats).hasSize(workspace.items().counts(course).approved());
        assertThat(stats).allMatch(s -> s.status() == ItemStatus.APPROVED);
        List<ItemStats> answered = stats.stream().filter(s -> s.accuracy() != null).toList();
        assertThat(stats.subList(0, answered.size())).as("answered questions come first").isEqualTo(answered);
        assertThat(answered).extracting(ItemStats::accuracy).isSorted();
    }
}
