package edu.learnsphere.core.db;

import edu.learnsphere.core.testing.TestDb;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Opens a fresh migrated database per test. */
abstract class RepositoryTestBase {

    @TempDir
    Path dir;
    TestDb t;
    Repositories repos;

    @BeforeEach
    void openDatabase() {
        t = TestDb.open(dir);
        repos = t.repos;
    }

    @AfterEach
    void closeDatabase() {
        t.close();
    }
}
