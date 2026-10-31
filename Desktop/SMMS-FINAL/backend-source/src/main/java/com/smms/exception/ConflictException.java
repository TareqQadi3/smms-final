package com.smms.exception;

/** A request that is valid on its own but conflicts with existing data (answered with 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String msg) { super(msg); }
}
