package dev.hunk.goland.action;

import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

public final class AddHunkNoteAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(AddHunkNoteAction.class);

    @Override
    public void update(@NotNull AnActionEvent event) {
        var enabled = event.getProject() != null
                && (event.getData(CommonDataKeys.EDITOR) != null
                || event.getData(DiffDataKeys.CURRENT_EDITOR) != null);
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }
        switch (ReviewTargetResolver.resolve(event)) {
            case TargetResolution.Resolved(var target, var editor, var editorLine) -> {
                LOG.info("event=review-target-resolved"
                        + " root=" + target.repositoryRoot()
                        + " file=" + target.filePath()
                        + " side=" + target.side().wireName()
                        + " reviewLine=" + target.line()
                        + " editorLine=" + editorLine
                        + " input=" + target.reviewInput().command());
                HunkLineInlay.show(project, editor, target, editorLine);
            }
            case TargetResolution.Unavailable(var reason) -> {
                LOG.warn("event=review-target-unavailable reason=" + reason);
                NotificationGroupManager.getInstance()
                        .getNotificationGroup("Hunk Session")
                        .createNotification(reason, NotificationType.WARNING)
                        .notify(project);
            }
        }
    }
}
