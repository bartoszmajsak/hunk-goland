package dev.hunk.goland.action;

import com.intellij.openapi.ide.CopyPasteManager;

import java.awt.datatransfer.StringSelection;

/** The agent handoff prompt shared with Hunk's terminal UI. */
final class HunkAgentPrompt {
    private static final String TEXT = String.join(
            " ",
            "Load the Hunk skill and use it for this review.",
            "Run `hunk skill path` to get the skill path."
    );

    private HunkAgentPrompt() {
    }

    static String text() {
        return TEXT;
    }

    static void copyToClipboard() {
        CopyPasteManager.getInstance().setContents(new StringSelection(TEXT));
    }
}
