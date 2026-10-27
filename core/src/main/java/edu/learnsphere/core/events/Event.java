package edu.learnsphere.core.events;

/** Something that changed, published on the {@link EventBus}. */
public sealed interface Event permits CourseChanged, DocumentChanged, ItemsChanged, JobProgress,
        AssistantStatusChanged, ProgressChanged, SettingChanged {
}
