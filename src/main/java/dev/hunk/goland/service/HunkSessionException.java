package dev.hunk.goland.service;

public final class HunkSessionException extends Exception {
    public HunkSessionException(String message) {
        super(message);
    }

    public HunkSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
