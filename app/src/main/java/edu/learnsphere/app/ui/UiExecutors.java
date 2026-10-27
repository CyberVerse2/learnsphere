package edu.learnsphere.app.ui;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where UI code runs blocking service calls: a small pool of platform threads ({@value #THREADS}). Service
 * calls mostly wait on SQLite, whose lock waits happen in native code and would pin virtual threads, and a
 * handful of threads is all one database serves well. Extra tasks wait in line. Use {@link UiTasks#run}
 * rather than this executor directly.
 */
public final class UiExecutors {

    /** Threads in the pool. */
    public static final int THREADS = 4;

    private static final ThreadPoolExecutor BACKGROUND = newPool();

    private UiExecutors() {
    }

    /** The shared pool. */
    public static ExecutorService background() {
        return BACKGROUND;
    }

    /**
     * Waits until no task is running or waiting, for at most {@code timeout} (screenshot tour, tests).
     *
     * @return false when the timeout elapsed first
     */
    public static boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (BACKGROUND.getTaskCount() != BACKGROUND.getCompletedTaskCount()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Stops accepting tasks (on exit); running tasks finish on their own. */
    public static void shutdown() {
        BACKGROUND.shutdown();
    }

    private static ThreadPoolExecutor newPool() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ui-task-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), factory);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
