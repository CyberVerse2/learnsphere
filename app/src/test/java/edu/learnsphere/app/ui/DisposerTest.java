package edu.learnsphere.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisposerTest {

    @Test
    void releasesEverythingOnceNewestFirst() {
        Disposer disposer = new Disposer();
        List<String> released = new ArrayList<>();
        CancellationToken work = CancellationToken.create();
        disposer.add(() -> released.add("subscription"));
        disposer.add(work);
        disposer.onDispose(() -> released.add("last"));

        disposer.dispose();
        disposer.dispose();

        assertThat(released).containsExactly("last", "subscription");
        assertThat(work.isCancelled()).isTrue();
        assertThat(disposer.isDisposed()).isTrue();
    }

    @Test
    void releasesWhatArrivesAfterDisposalRightAway() {
        Disposer disposer = new Disposer();
        disposer.dispose();
        List<String> released = new ArrayList<>();

        disposer.add(() -> released.add("late subscription"));

        assertThat(released).containsExactly("late subscription");
    }

    @Test
    void oneFailingReleaseDoesNotStopTheOthers() {
        Disposer disposer = new Disposer();
        List<String> released = new ArrayList<>();
        disposer.onDispose(() -> released.add("first"));
        disposer.onDispose(() -> {
            throw new IllegalStateException("broken");
        });

        disposer.dispose();

        assertThat(released).containsExactly("first");
    }
}
