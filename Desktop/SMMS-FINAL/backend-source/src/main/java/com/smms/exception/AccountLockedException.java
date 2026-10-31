package com.smms.exception;

/** Thrown when a login is rejected because the account is temporarily locked (HTTP 429). */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String msg) { super(msg); }
}
