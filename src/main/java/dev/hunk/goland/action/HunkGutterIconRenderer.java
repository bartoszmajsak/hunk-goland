package dev.hunk.goland.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import dev.hunk.goland.model.ReviewTarget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** Clickable, provenance-aware marker that opens the existing inline Hunk thread. */
final class HunkGutterIconRenderer extends GutterIconRenderer {
    private static final Icon REVIEW = icon("/icons/hunkReviewComment.svg");
    private static final Icon AGENT = icon("/icons/hunkAgentResponse.svg");
    private static final Icon MIXED = icon("/icons/hunkMixedThread.svg");

    private final Project project;
    private final Editor editor;
    private final ReviewTarget target;
    private final int editorLine;
    private final HunkGutterSummary summary;

    HunkGutterIconRenderer(
            Project project,
            Editor editor,
            ReviewTarget target,
            int editorLine,
            HunkGutterSummary summary
    ) {
        this.project = project;
        this.editor = editor;
        this.target = target;
        this.editorLine = editorLine;
        this.summary = summary;
    }

    @Override
    public @NotNull Icon getIcon() {
        return switch (summary.kind()) {
            case REVIEW -> REVIEW;
            case AGENT -> AGENT;
            case MIXED -> MIXED;
        };
    }

    @Override
    public @Nullable String getTooltipText() {
        return summary.tooltip();
    }

    @Override
    public @NotNull Alignment getAlignment() {
        return Alignment.RIGHT;
    }

    @Override
    public @Nullable AnAction getClickAction() {
        return new DumbAwareAction("Open Hunk inline review") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                if (project.isDisposed() || editor.isDisposed()) {
                    return;
                }
                var line = Math.clamp(editorLine - 1, 0, editor.getDocument().getLineCount() - 1);
                editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, 0));
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                HunkLineInlay.show(project, editor, target, editorLine);
            }
        };
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof HunkGutterIconRenderer other
                && editor == other.editor
                && target.equals(other.target)
                && editorLine == other.editorLine
                && summary.equals(other.summary);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(editor)
                + 17 * target.hashCode()
                + 13 * editorLine
                + summary.hashCode();
    }

    private static Icon icon(String path) {
        return IconLoader.getIcon(path, HunkGutterIconRenderer.class);
    }
}
