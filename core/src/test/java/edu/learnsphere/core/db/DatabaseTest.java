package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.model.Course;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path dir;
    TestDb t;

    @BeforeEach
    void open() {
        t = TestDb.open(dir);
    }

    @AfterEach
    void close() {
        t.close();
    }

    @Test
    void repositoryCallsInsideATransactionRollBackTogether() {
        assertThatThrownBy(() -> t.db.useTransaction(h -> {
            assertThat(t.db.isInTransaction()).isTrue();
            t.course("First");
            t.course("Second");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(t.repos.courses().list()).isEmpty();
        assertThat(t.db.isInTransaction()).isFalse();
    }

    @Test
    void nestedTransactionsJoinTheOuterOne() {
        t.db.useTransaction(h -> {
            t.course("Outer");
            t.db.useTransaction(inner -> t.course("Inner"));
        });
        assertThat(t.repos.courses().list()).extracting(Course::title).containsExactlyInAnyOrder("Outer", "Inner");
    }

    @Test
    void handlesConcurrentWriters() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                futures.add(pool.submit(() -> t.db.useTransaction(h -> t.course("Course " + n))));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(t.repos.courses().list()).hasSize(20);
    }

    @Test
    void reportsConstraintViolations() {
        Course course = t.course("Dup");
        assertThatThrownBy(() -> t.repos.courses().insert(course))
                .satisfies(e -> assertThat(Database.isConstraintViolation(e)).isTrue());
        assertThat(Database.isConstraintViolation(new RuntimeException("x"))).isFalse();
    }

    @Test
    void checkpointLeavesDataReadable() {
        t.course("Saved");
        t.db.checkpoint();
        assertThat(t.repos.courses().list()).hasSize(1);
    }
}
