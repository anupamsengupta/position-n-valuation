package com.power.posval.domain.exception;

/**
 * Runtime exception wrapping failures during volume materialization or S6b rebuild.
 * Wraps InterruptedException/ExecutionException from virtual-thread processing.
 */
public class MaterializationException extends RuntimeException {

    public MaterializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
