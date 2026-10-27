/**
 * Interfaces core needs from other modules, with their data types. Implementations: PDF handling in the
 * {@code pdf} module, {@link edu.learnsphere.core.spi.SearchIndex} in {@code search}, AI capabilities in
 * {@code ai} (with deterministic fakes in {@code edu.learnsphere.ai.fake}). {@code app} wires them.
 *
 * <p>All implementations are thread-safe. AI calls block, accept a
 * {@link edu.learnsphere.core.util.CancellationToken}, throw
 * {@link edu.learnsphere.core.error.UnavailableException} while the study assistant is not ready and
 * {@link java.util.concurrent.CancellationException} when cancelled.
 */
package edu.learnsphere.core.spi;
