/**
 * Local AI for LearnSphere: implementations of the AI interfaces in {@code edu.learnsphere.core.spi}
 * backed by bundled llama.cpp {@code llama-server} processes. {@link edu.learnsphere.ai.LocalAi} builds the
 * whole {@code AiCapabilities} bundle; nothing runs until the runtime is started or a request needs it.
 *
 * <ul>
 *   <li>{@code edu.learnsphere.ai.runtime}: finding the runtime folder, hardware checks, starting and
 *       supervising the server processes ({@code AiRuntime}).</li>
 *   <li>{@code edu.learnsphere.ai.client}: HTTP clients for chat, embeddings and reranking, and the
 *       request gate that orders chat requests by priority.</li>
 *   <li>{@code edu.learnsphere.ai.prompt}: prompt templates and structured-output schemas.</li>
 *   <li>{@code edu.learnsphere.ai.fake}: deterministic fakes for every AI interface, used by tests, the
 *       screenshot tour and demo data.</li>
 * </ul>
 */
package edu.learnsphere.ai;
