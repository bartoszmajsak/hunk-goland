package dev.hunk.goland.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/** Opens the same commented-file chooser exposed by the Hunk status-bar indicator. */
public final class ShowHunkCommentedFilesAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project != null) {
            var input = event.getInputEvent();
            HunkReviewStatusBarWidget.showFileChooser(
                    project,
                    input == null ? null : input.getComponent()
            );
        }
    }
}
