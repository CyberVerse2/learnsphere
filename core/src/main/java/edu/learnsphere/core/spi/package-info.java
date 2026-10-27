/**
 * Interfaces core needs from other modules, with their data types. Implementations: PDF handling in the
 * {@code pdf} module, {@link edu.learnsphere.core.spi.SearchIndex} in {@code search}, AI capabilities in
 * {@code ai} (with deterministic fakes in {@code edu.learnsphere.ai.fake}). {@code app} collects them in
 * one {@link edu.learnsphere.core.spi.Capabilities} and passes it to {@code ProfileServices.create}; every
 * service implementation receives it through its workspace. The search index is per profile, so the
 * search module provides a {@link edu.learnsphere.core.spi.SearchIndexFactory}, not an index.
 *
 * <p>All implementations are thread-safe. AI calls block, accept a
 * {@link edu.learnsphere.core.util.CancellationToken}, throw
 * {@link edu.learnsphere.core.error.UnavailableException} while the study assistant is not ready and
 * {@link java.util.concurrent.CancellationException} when cancelled.
 */
package edu.learnsphere.core.spi;
