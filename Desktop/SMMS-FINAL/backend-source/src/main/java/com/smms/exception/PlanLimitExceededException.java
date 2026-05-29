package com.smms.exception;

/** Thrown when a tenant's plan limit is reached. Maps to HTTP 402 Payment Required. */
public class PlanLimitExceededException extends RuntimeException {
    public PlanLimitExceededException(String message) { super(message); }
}
