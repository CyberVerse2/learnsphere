/**
 * The AI pipelines of a workspace, as described in {@code docs/ARCHITECTURE.md} under "Pipelines".
 *
 * <ul>
 *   <li>{@link edu.learnsphere.core.service.impl.pipeline.IngestionService}: the
 *       {@link edu.learnsphere.core.model.JobKind#INGEST_DOCUMENT} job handler that turns an imported PDF into
 *       searchable passages (check, extract, read scanned pages, describe figures, chunk, embed, index).</li>
 *   <li>{@link edu.learnsphere.core.service.impl.pipeline.AskServiceImpl}: questions answered only from a
 *       course's material, streamed with validated page citations.</li>
 *   <li>{@link edu.learnsphere.core.service.impl.pipeline.GenerationServiceImpl}: concept extraction, item
 *       generation and regeneration, each as a job.</li>
 * </ul>
 *
 * <p>Everything here depends only on core: repositories, the event bus, the job queue, settings and the SPI
 * interfaces. Background jobs reach the study assistant through {@code BackgroundAi}, which waits while the
 * assistant is starting; answering a question reports the assistant's state at once instead.
 */
package edu.learnsphere.core.service.impl.pipeline;
