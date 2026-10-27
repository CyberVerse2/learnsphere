/**
 * SQLite access: {@link edu.learnsphere.core.db.Database} (connections, transactions),
 * {@link edu.learnsphere.core.db.Migrations} (schema), and one JDBI-backed repository per aggregate,
 * bundled in {@link edu.learnsphere.core.db.Repositories}. Repositories are used only by services.
 *
 * <p>Repositories bind enum columns by their lowercase codes, stamp {@code updated_at} from the injected
 * clock on updates, and insert records exactly as given. Single-row update methods return {@code true}
 * when a row changed; {@code get} methods throw {@link edu.learnsphere.core.error.NotFoundException}.
 */
package edu.learnsphere.core.db;
