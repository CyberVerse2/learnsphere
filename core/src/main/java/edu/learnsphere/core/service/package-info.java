/**
 * What the UI calls: service interfaces (implemented in {@code edu.learnsphere.core.service.impl}) and
 * the view-ready records they return. One call gives a screen what it shows; there is no need to call
 * per row.
 *
 * <p>Services are thread-safe and blocking: call them off the FX thread and apply results with
 * {@code Fx.run}. Failures are {@link edu.learnsphere.core.error.LearnSphereException} subclasses whose
 * {@link edu.learnsphere.core.error.UserMessage} is the only error text to show. Changes are announced on
 * the workspace's {@link edu.learnsphere.core.events.EventBus}.
 */
package edu.learnsphere.core.service;
