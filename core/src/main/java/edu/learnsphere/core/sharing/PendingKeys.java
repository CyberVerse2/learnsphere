package edu.learnsphere.core.sharing;

import edu.learnsphere.core.util.Ids;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Keys of protected packages waiting for their import job, held in memory only: a job payload carries an
 * opaque reference, never the code or the key, so a job resumed after a restart finds no key and fails.
 * Thread-safe.
 */
public final class PendingKeys implements AutoCloseable {

    private final Map<String, Pending> keys = new ConcurrentHashMap<>();

    private static final class Pending {

        final PackageKey key;
        volatile String jobId;

        Pending(PackageKey key) {
            this.key = key;
        }
    }

    /** Holds {@code key} and returns the reference to put in the job payload. */
    public String put(PackageKey key) {
        Objects.requireNonNull(key, "key");
        String ref = Ids.newId();
        keys.put(ref, new Pending(key));
        return ref;
    }

    /** Records which job a held key belongs to, so {@link #purge} can drop it when that job ends. */
    public void attach(String ref, String jobId) {
        Pending pending = keys.get(ref);
        if (pending != null) {
            pending.jobId = jobId;
        }
    }

    /** Removes and returns the key for {@code ref}; the caller closes it. Null when there is none. */
    public PackageKey take(String ref) {
        if (ref == null) {
            return null;
        }
        Pending pending = keys.remove(ref);
        return pending == null ? null : pending.key;
    }

    /** Closes and removes the key for {@code ref}, if held. */
    public void discard(String ref) {
        PackageKey key = take(ref);
        if (key != null) {
            key.close();
        }
    }

    /** Closes and drops keys whose job is no longer active (for example cancelled before it ran). */
    public void purge(Predicate<String> isJobActive) {
        keys.entrySet().removeIf(e -> {
            String jobId = e.getValue().jobId;
            if (jobId != null && !isJobActive.test(jobId)) {
                e.getValue().key.close();
                return true;
            }
            return false;
        });
    }

    /** Number of keys held. */
    public int size() {
        return keys.size();
    }

    /** Closes and drops every key. */
    @Override
    public void close() {
        keys.values().forEach(p -> p.key.close());
        keys.clear();
    }
}
