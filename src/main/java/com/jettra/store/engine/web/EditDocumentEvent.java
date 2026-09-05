package com.jettra.store.engine.web;

/**
 * Sealed hierarchy of Reactive Events published after editing an entity or creating a new version.
 */
public sealed interface EditDocumentEvent permits EditDocumentSuccessEvent, EditDocumentFailureEvent {
    EditDocumentCommand command();
    long eventTimestamp();
}
