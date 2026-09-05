package com.jettra.store.engine.web;

public record EditDocumentSuccessEvent(
    EditDocumentCommand command,
    EditDocumentResult result,
    long eventTimestamp
) implements EditDocumentEvent {}
