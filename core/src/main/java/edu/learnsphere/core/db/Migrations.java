package edu.learnsphere.core.db;

import edu.learnsphere.core.model.SchemaVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the numbered SQL scripts {@code db/migration/V{n}__description.sql} found on the class path,
 * in order of {@code n}, each in its own transaction together with its {@code schema_version} row.
 * Running it again applies only scripts that are not recorded yet.
 *
 * <p>Scripts are split into statements at semicolons outside quotes and comments; a
 * {@code CREATE TRIGGER ... END;} block stays one statement. Never edit an applied script: add a new one.
 */
public final class Migrations {

    private static final Logger LOG = LoggerFactory.getLogger(Migrations.class);

    /** Class path folder holding the scripts. */
    public static final String DEFAULT_LOCATION = "db/migration";

    private static final Pattern SCRIPT_NAME = Pattern.compile("V(\\d+)__([A-Za-z0-9_.-]+)\\.sql");

    private final Database database;
    private final Clock clock;
    private final ClassLoader classLoader;
    private final String location;

    public Migrations(Database database, Clock clock) {
        this(database, clock, Migrations.class.getClassLoader(), DEFAULT_LOCATION);
    }

    /** Uses scripts from another class path folder (tests). */
    public Migrations(Database database, Clock clock, ClassLoader classLoader, String location) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.location = Objects.requireNonNull(location, "location");
    }

    /** A script found on the class path. */
    public record Script(int version, String name, String sql) {
    }

    /**
     * Applies every pending script.
     *
     * @return the versions applied by this call, oldest first (empty when the schema was current)
     */
    public List<SchemaVersion> migrate() {
        ensureVersionTable();
        int current = currentVersion();
        List<SchemaVersion> applied = new ArrayList<>();
        for (Script script : scripts()) {
            if (script.version() <= current) {
                continue;
            }
            long now = clock.millis();
            database.useTransaction(handle -> {
                for (String statement : splitStatements(script.sql())) {
                    handle.execute(statement);
                }
                handle.createUpdate("INSERT INTO schema_version (version, name, applied_at) VALUES (:v, :n, :t)")
                        .bind("v", script.version())
                        .bind("n", script.name())
                        .bind("t", now)
                        .execute();
            });
            LOG.info("Applied migration {}", script.name());
            applied.add(new SchemaVersion(script.version(), script.name(), now));
        }
        return applied;
    }

    /** The highest applied version, or 0 for an empty database. */
    public int currentVersion() {
        ensureVersionTable();
        return database.withHandle(handle -> handle.createQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")
                .mapTo(Integer.class)
                .one());
    }

    /** Every applied migration, oldest first. */
    public List<SchemaVersion> applied() {
        ensureVersionTable();
        return database.withHandle(handle -> handle.createQuery(
                        "SELECT version, name, applied_at FROM schema_version ORDER BY version")
                .map((rs, ctx) -> new SchemaVersion(rs.getInt("version"), rs.getString("name"), rs.getLong("applied_at")))
                .list());
    }

    /** The scripts on the class path, ordered by version. */
    public List<Script> scripts() {
        Map<Integer, Script> byVersion = new TreeMap<>();
        for (String fileName : scriptNames()) {
            Matcher matcher = SCRIPT_NAME.matcher(fileName);
            if (!matcher.matches()) {
                continue;
            }
            int version = Integer.parseInt(matcher.group(1));
            Script script = new Script(version, fileName, read(location + "/" + fileName));
            Script clash = byVersion.put(version, script);
            if (clash != null && !clash.name().equals(fileName)) {
                throw new IllegalStateException("Two migrations with version " + version + ": " + clash.name() + ", " + fileName);
            }
        }
        return List.copyOf(byVersion.values());
    }

    private void ensureVersionTable() {
        database.useHandle(handle -> handle.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version    INTEGER PRIMARY KEY,
                    name       TEXT    NOT NULL,
                    applied_at INTEGER NOT NULL
                ) STRICT"""));
    }

    private List<String> scriptNames() {
        List<String> names = new ArrayList<>();
        try {
            Enumeration<URL> roots = classLoader.getResources(location);
            for (URL root : Collections.list(roots)) {
                names.addAll(list(root));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list migrations in " + location, e);
        }
        return names;
    }

    private static List<String> list(URL root) throws IOException {
        URI uri;
        try {
            uri = root.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Bad migration location " + root, e);
        }
        if ("jar".equals(uri.getScheme())) {
            FileSystem fs;
            boolean created = false;
            try {
                fs = FileSystems.newFileSystem(uri, Map.of());
                created = true;
            } catch (FileSystemAlreadyExistsException e) {
                fs = FileSystems.getFileSystem(uri);
            }
            try {
                String inner = uri.getSchemeSpecificPart();
                return listDirectory(fs.getPath(inner.substring(inner.indexOf("!/") + 1)));
            } finally {
                if (created) {
                    fs.close();
                }
            }
        }
        return listDirectory(Path.of(uri));
    }

    private static List<String> listDirectory(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    private String read(String resource) {
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }

    /**
     * Splits a script into statements at semicolons that are outside quotes and comments. Comments are
     * dropped. A {@code CREATE TRIGGER} statement continues until its closing {@code END;}.
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                while (i < length && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                current.append(' ');
                continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int start = i;
                i++;
                while (i < length) {
                    if (sql.charAt(i) == close) {
                        if (close != ']' && i + 1 < length && sql.charAt(i + 1) == close) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i = Math.min(i + 1, length);
                current.append(sql, start, i);
                continue;
            }
            if (c == ';') {
                String statement = current.toString().strip();
                if (isOpenTrigger(statement)) {
                    current.append(c);
                    i++;
                    continue;
                }
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static boolean isOpenTrigger(String statement) {
        String upper = statement.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean trigger = upper.startsWith("CREATE TRIGGER") || upper.startsWith("CREATE TEMP TRIGGER")
                || upper.startsWith("CREATE TEMPORARY TRIGGER");
        return trigger && !upper.endsWith(" END");
    }
}
