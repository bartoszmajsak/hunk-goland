package dev.hunk.goland.action;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;

/** Opens an indexed Hunk line thread in the most natural current/new-side editor. */
final class HunkThreadNavigator {
    private HunkThreadNavigator() {
    }

    static void open(
            Project project,
            HunkReviewIndex index,
            HunkReviewIndex.ThreadLocation location,
            TargetResolution.Resolved origin
    ) {
        var target = new ReviewTarget(
                index.repositoryRoot(),
                location.filePath(),
                ReviewSide.NEW,
                location.line(),
                index.reviewInput()
        );
        if (origin != null
                && !origin.editor().isDisposed()
                && origin.target().filePath().equals(location.filePath())) {
            reveal(origin.editor(), target);
            return;
        }

        var absolutePath = index.repositoryRoot().resolve(location.filePath()).normalize();
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolutePath);
        if (file == null) {
            notify(project, "Could not open " + location.filePath() + " from this Hunk review.");
            return;
        }
        var descriptor = new OpenFileDescriptor(project, file, location.line() - 1, 0);
        descriptor.setScrollType(ScrollType.CENTER);
        var editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        if (editor == null) {
            notify(project, "Could not open a text editor for " + location.displayName() + ".");
            return;
        }
        reveal(editor, target);
    }

    private static void reveal(Editor editor, ReviewTarget target) {
        var editorLine = ReviewTargetResolver.editorLine(editor, target.side(), target.line());
        if (editorLine.isEmpty()) {
            notify(editor.getProject(), "Could not map " + target.displayName() + " into this editor.");
            return;
        }
        var oneBasedLine = editorLine.getAsInt();
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(oneBasedLine - 1, 0));
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        HunkLineInlay.show(editor.getProject(), editor, target, oneBasedLine);
    }

    private static void notify(Project project, String message) {
        if (project == null || project.isDisposed()) {
            return;
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Hunk Session")
                .createNotification(message, NotificationType.WARNING)
                .notify(project);
    }
}
