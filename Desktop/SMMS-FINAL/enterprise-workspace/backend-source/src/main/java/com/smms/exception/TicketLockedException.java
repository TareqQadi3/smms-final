package com.smms.exception;

public class TicketLockedException extends RuntimeException {
    public TicketLockedException(String msg) { super(msg); }
}
