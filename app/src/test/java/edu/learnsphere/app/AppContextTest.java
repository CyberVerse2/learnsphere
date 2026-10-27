package edu.learnsphere.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.app.testing.GatedRuntime;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The open profile: opening, switching, following changes and closing. */
class AppContextTest {

    @TempDir
    Path dir;

    private RealWorkspaces app;
    private final GatedRuntime assistant = new GatedRuntime();

    @AfterEach
    void close() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void aWrongPasswordForAnotherProfileKeepsTheOpenOne() {
        app = RealWorkspaces.at(dir, assistant);
        AppContext context = app.context();
        ProfileSummary ada = context.profiles().create(new ProfileDraft("Ada", 210, null));
        ProfileSummary grace = context.profiles().create(new ProfileDraft("Grace", 30, "cobol".toCharArray()));
        Workspace first = context.open(ada.id(), null);
        published(context, first);

        assertThatThrownBy(() -> context.open(grace.id(), "fortran".toCharArray()))
                .isInstanceOf(LearnSphereException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage().text())
                .isEqualTo("That password isn't right. Try again.");
        assertThat(context.openProfileId()).isEqualTo(ada.id());
        assertThat(assistant.stops()).isZero();

        Workspace second = context.open(grace.id(), "cobol".toCharArray());
        published(context, second);
        assertThat(context.openProfileId()).isEqualTo(grace.id());
        assertThat(context.profile().id()).isEqualTo(grace.id());
        assertThat(assistant.stops()).isEqualTo(1);
        assertThat(context.preferences().get().lastProfileId()).isEqualTo(grace.id());
        assertThat(context.open(grace.id(), null)).as("already open").isSameAs(second);
    }

    @Test
    void theLastProfileIsTheOneUsedLastOrTheMostRecent() {
        app = RealWorkspaces.at(dir, assistant);
        AppContext context = app.context();
        assertThat(context.lastProfile()).isEmpty();
        ProfileSummary ada = context.profiles().create(new ProfileDraft("Ada", 210, null));
        assertThat(context.lastProfile()).map(ProfileSummary::id).contains(ada.id());
        ProfileSummary grace = context.profiles().create(new ProfileDraft("Grace", 30, "cobol".toCharArray()));
        context.preferences().update(p -> p.withLastProfileId(grace.id()));
        assertThat(context.lastProfile()).map(ProfileSummary::id).contains(grace.id());
        assertThat(context.openLastProfile()).as("a password is needed").isEmpty();
        context.preferences().update(p -> p.withLastProfileId("0f0f0f0f-0000-4000-8000-000000000000"));
        assertThat(context.lastProfile()).as("a removed profile falls back").isPresent();
    }

    @Test
    void theProfileFollowsRenamesAndClosingClearsIt() {
        app = RealWorkspaces.open(dir);
        AppContext context = app.context();
        String id = context.openProfileId();
        context.profiles().rename(id, "Ada King");
        context.refreshProfile();
        RealWorkspaces.await(() -> "Ada King".equals(context.profile().displayName()), Duration.ofSeconds(5),
                "the new name");

        context.closeWorkspace();
        RealWorkspaces.await(() -> context.workspace() == null && context.profile() == null, Duration.ofSeconds(5),
                "the profile to close");
        assertThat(context.openProfileId()).isNull();
        context.profiles().delete(id);
        assertThat(context.profiles().find(id)).as("a closed profile can be deleted").isEmpty();
    }

    @Test
    void closingTwiceIsHarmless() {
        app = RealWorkspaces.open(dir);
        app.context().close();
        app.context().close();
        assertThat(app.context().isClosed()).isTrue();
        assertThat(app.context().openProfileId()).isNull();
    }

    private static void published(AppContext context, Workspace workspace) {
        RealWorkspaces.await(() -> context.workspace() == workspace, Duration.ofSeconds(10), "the profile to show");
    }
}
