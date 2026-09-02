package dev.hunk.goland.service;

import com.intellij.openapi.diagnostic.Logger;
import dev.hunk.goland.settings.HunkSettings;

import java.util.function.Supplier;

/** Opt-in, structurally redacted diagnostics written to GoLand's normal {@code idea.log}. */
final class HunkDiagnostics {
    private static final String EVENT_PREFIX = "event=hunk-diagnostic-";

    private HunkDiagnostics() {
    }

    static boolean enabled() {
        return Boolean.getBoolean("hunk.diagnostics")
                || HunkSettings.getInstance().getState().diagnosticsEnabled;
    }

    static void info(Logger logger, String event, Supplier<String> details) {
        if (enabled()) {
            logger.info(EVENT_PREFIX + event + " " + details.get());
        }
    }

    static void warning(Logger logger, String event, Supplier<String> details) {
        if (enabled()) {
            logger.warn(EVENT_PREFIX + event + " " + details.get());
        }
    }
}
