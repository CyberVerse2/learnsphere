package edu.learnsphere.app.ui.studio;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The Studio at the smallest window DESIGN.md supports (1100 by 900): publishing and the scanned-page editor
 * rearrange themselves so nothing is cut off. Set {@code LEARNSPHERE_STUDIO_SNAPSHOTS} to a folder to also
 * save what each test sees as PNG files.
 */
@ExtendWith(ApplicationExtension.class)
class StudioNarrowWindowTest {

    private static final int WIDTH = 1100;
    private static final int HEIGHT = 900;

    @TempDir
    Path dir;

    private RealWorkspaces real;
    private MainWindow window;

    @Start
    void start(Stage stage) {
        real = RealWorkspaces.open(dir);
        window = new MainWindow(real.context());
        Scene scene = new Scene(window, WIDTH, HEIGHT);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        robot.interact(window::dispose);
        real.close();
    }

    @Test
    void publishingStacksSharingBelowAndKeepsEveryLedgerLabelWhole(FxRobot robot) throws Exception {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry Basics"));
        LearningItem approved = ws().items().create(course.id(), null,
                ItemContent.flashcard("What is an isomer?", "A molecule with the same formula and a different structure."));
        ws().items().approve(approved.id());
        ws().items().create(course.id(), null, ItemContent.flashcard("What is a functional group?", "A reactive group."));
        LearningItem rejected = ws().items().create(course.id(), null, ItemContent.flashcard("Is benzene flat?", "Yes."));
        ws().items().reject(rejected.id());
        ws().courses().publish(course.id(), "First edition for the spring term.");
        ws().items().addFlag(approved.id(), FlagReason.UNCLEAR, "Which kind of isomer?");
        open(robot, new Destination.Studio(course.id(), StudioTab.PUBLISH));

        PublishSection section = (PublishSection) page().section();
        assertThat(section.sideBySide()).isFalse();
        List<Labeled> ledger = labels(robot, ".ledger-label");
        assertThat(ledger).extracting(Labeled::getText)
                .containsExactly("approved", "drafts to review", "flagged", "rejected or retired");
        assertThat(ledger).noneMatch(StudioNarrowWindowTest::truncated);
        assertThat(labels(robot, ".publish-version")).noneMatch(StudioNarrowWindowTest::truncated);
        snapshot("studio-publish-1100");
    }

    @Test
    void theScannedPageShrinksSoTheEditorKeepsItsRoom(FxRobot robot) throws Exception {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry Basics"));
        DocumentSummary scanned = ws().documents().importPdf(course.id(), null, real.scannedPdf("handout.pdf", 3));
        assertThat(real.awaitSettled(scanned.id()).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        open(robot, new Destination.Studio(course.id(), StudioTab.PAGES));
        ScannedPagesSection pages = (ScannedPagesSection) page().section();
        waitFor(() -> pages.pageOnScreen() == 1 && !pages.textArea().isDisabled());
        settle();

        assertThat(labels(robot, ".scanned-page-title")).extracting(Labeled::getText).containsExactly("Page 1");
        assertThat(labels(robot, ".scanned-page-title")).noneMatch(StudioNarrowWindowTest::truncated);
        assertThat(labels(robot, ".approve-page")).noneMatch(StudioNarrowWindowTest::truncated);
        Region image = robot.lookup(".scanned-image").queryAs(Region.class);
        Region editor = robot.lookup(".scanned-editor").queryAs(Region.class);
        assertThat(image.getWidth()).isLessThan(480);
        assertThat(editor.getWidth()).isGreaterThan(image.getWidth());
        snapshot("studio-pages-1100");
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------------

    /** True when a label shows its text cut short with an ellipsis. */
    private static boolean truncated(Labeled labeled) {
        Node shown = labeled.lookup(".text");
        return shown instanceof Text text && !text.getText().equals(labeled.getText());
    }

    private static List<Labeled> labels(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream().filter(Node::isVisible).map(Labeled.class::cast).toList();
    }

    private void snapshot(String name) throws IOException {
        String folder = System.getenv("LEARNSPHERE_STUDIO_SNAPSHOTS");
        if (folder == null || folder.isBlank()) {
            return;
        }
        Path file = Files.createDirectories(Path.of(folder)).resolve(name + ".png");
        var image = WaitForAsyncUtils.asyncFx(() -> window.getScene().snapshot(null));
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(10, TimeUnit.SECONDS), null), "png", file.toFile());
        } catch (Exception e) {
            throw new IOException("Couldn't save " + file, e);
        }
    }

    private Workspace ws() {
        return real.workspace();
    }

    private StudioPage page() {
        return (StudioPage) window.navigator().currentView();
    }

    private void open(FxRobot robot, Destination.Studio destination) {
        robot.interact(() -> window.navigator().navigate(destination));
        settle();
        settle();
    }

    private static void settle() {
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void waitFor(BooleanSupplier condition) throws TimeoutException {
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS, () -> {
            WaitForAsyncUtils.waitForFxEvents();
            return condition.getAsBoolean();
        });
    }
}
