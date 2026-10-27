/**
 * Deterministic, dependency-free fakes for every AI interface in {@code edu.learnsphere.core.spi}. They
 * need no model files, return the same output for the same input, and honour cancellation tokens. Tests,
 * the screenshot tour and demo mode use them; {@link edu.learnsphere.ai.fake.FakeAi} bundles one of each.
 */
package edu.learnsphere.ai.fake;
