package com.jettra.store.engine.web;

import java.util.function.Consumer;

/**
 * Observer interface for reacting to document version modification events.
 */
@FunctionalInterface
public interface EditDocumentObserver extends Consumer<EditDocumentEvent> {
    void onEditEvent(EditDocumentEvent event);

    @Override
    default void accept(EditDocumentEvent event) {
        onEditEvent(event);
    }
}
