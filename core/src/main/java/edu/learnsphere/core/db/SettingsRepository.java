package edu.learnsphere.core.db;

import edu.learnsphere.core.model.Setting;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;

/** Raw key/value settings. Typed access is {@code SettingsService}'s job. */
public final class SettingsRepository {

    static final RowMapper<Setting> SETTING = (rs, ctx) -> new Setting(
            rs.getString("key"),
            rs.getString("value"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final Database db;
    private final Clock clock;

    public SettingsRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The stored value, if any. */
    public Optional<String> get(String key) {
        return find(key).map(Setting::value);
    }

    public Optional<Setting> find(String key) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM settings WHERE key = :k").bind("k", key).map(SETTING).findOne());
    }

    /** Stores {@code value} under {@code key}, replacing any previous value. */
    public void put(String key, String value) {
        long now = clock.millis();
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO settings (key, value, created_at, updated_at) VALUES (:k, :v, :now, :now)
                        ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                        """)
                .bind("k", key).bind("v", value).bind("now", now).execute());
    }

    /** Every stored setting, by key. */
    public Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        db.withHandle(h -> h.createQuery("SELECT * FROM settings ORDER BY key").map(SETTING).list())
                .forEach(s -> result.put(s.key(), s.value()));
        return result;
    }

    /** Removes a setting so its default applies again. */
    public boolean delete(String key) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM settings WHERE key = :k").bind("k", key).execute()) > 0;
    }
}
