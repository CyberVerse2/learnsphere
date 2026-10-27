/**
 * The local AI runtime: finding the runtime folder ({@link edu.learnsphere.ai.runtime.RuntimeLocator}),
 * checking the hardware ({@link edu.learnsphere.ai.runtime.HardwareProbe}), locating model files
 * ({@link edu.learnsphere.ai.runtime.ModelCatalog}), and starting and supervising the three
 * {@code llama-server} processes ({@link edu.learnsphere.ai.runtime.LlamaServerManager}, the app's
 * {@code AiRuntime}).
 */
package edu.learnsphere.ai.runtime;
