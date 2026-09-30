package dev.hunk.goland.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/** Opens the persistent review-wide navigator exposed by the Hunk status-bar indicator. */
public final class ShowHunkCommentedFilesAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project != null) {
            HunkReviewToolWindowFactory.show(project);
        }
    }
}
