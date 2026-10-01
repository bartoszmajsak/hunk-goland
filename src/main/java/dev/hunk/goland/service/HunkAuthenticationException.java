package dev.hunk.goland.service;

import java.io.IOException;

/** Identity/protocol failures are not evidence that the daemon is absent. Contains no input/cause. */
final class HunkAuthenticationException extends IOException {
    HunkAuthenticationException(String message) { super(message); }
}
