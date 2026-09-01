package com.jettra.store.engine.hierarchy;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Functional Result/Either pattern for fail-safe hierarchy resolution and operations.
 * Implemented using Java 25 sealed interfaces and records.
 */
public sealed interface HierarchyResult<T> permits HierarchyResult.Success, HierarchyResult.Failure {

    boolean isSuccess();

    default boolean isFailure() {
        return !isSuccess();
    }

    T getOrNull();

    String errorMessage();

    static <T> HierarchyResult<T> success(T value) {
        return new Success<>(Objects.requireNonNull(value, "Success value cannot be null"));
    }

    static <T> HierarchyResult<T> failure(String message) {
        return new Failure<>(message != null ? message : "Unknown error");
    }

    static <T> HierarchyResult<T> failure(String message, Throwable cause) {
        String msg = message != null ? message : (cause != null ? cause.getMessage() : "Unknown error");
        return new Failure<>(msg, cause);
    }

    default <R> HierarchyResult<R> map(Function<? super T, ? extends R> mapper) {
        return switch (this) {
            case Success<T>(var val) -> HierarchyResult.success(mapper.apply(val));
            case Failure<T>(var msg, var cause) -> new Failure<>(msg, cause);
        };
    }

    default HierarchyResult<T> onSuccess(Consumer<? super T> action) {
        if (this instanceof Success<T>(var val)) {
            action.accept(val);
        }
        return this;
    }

    default HierarchyResult<T> onFailure(Consumer<String> action) {
        if (this instanceof Failure<T>(var msg, var cause)) {
            action.accept(msg);
        }
        return this;
    }

    record Success<T>(T value) implements HierarchyResult<T> {
        @Override public boolean isSuccess() { return true; }
        @Override public T getOrNull() { return value; }
        @Override public String errorMessage() { return null; }
    }

    record Failure<T>(String message, Throwable cause) implements HierarchyResult<T> {
        public Failure(String message) {
            this(message, null);
        }
        @Override public boolean isSuccess() { return false; }
        @Override public T getOrNull() { return null; }
        @Override public String errorMessage() { return message; }
    }
}
