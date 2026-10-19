package com.dukankhata.auth;

public class SessionRevocationUnavailableException extends RuntimeException {
    public SessionRevocationUnavailableException() {
        super("Authentication is temporarily unavailable. Please try again shortly.");
    }
}
