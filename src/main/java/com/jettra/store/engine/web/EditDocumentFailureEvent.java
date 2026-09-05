package com.jettra.store.engine.web;

public record EditDocumentFailureEvent(
    EditDocumentCommand command,
    String failureReason,
    long eventTimestamp
) implements EditDocumentEvent {}
