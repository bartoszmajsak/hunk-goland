package dev.hunk.goland.action;

import com.intellij.openapi.editor.Editor;
import dev.hunk.goland.model.ReviewTarget;

public sealed interface TargetResolution {
    /** A native Hunk target plus its one-based visual line in the current editor. */
    record Resolved(ReviewTarget target, Editor editor, int editorLine) implements TargetResolution {
        public Resolved {
            if (editorLine < 1) {
                throw new IllegalArgumentException("editorLine must be positive");
            }
        }
    }

    record Unavailable(String reason) implements TargetResolution {
    }
}
