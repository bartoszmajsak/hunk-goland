package dev.hunk.goland.action;

import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import org.jetbrains.annotations.NotNull;

abstract class HunkThreadNavigationAction extends AnAction {
    static final String PREVIOUS_ACTION_ID = "dev.hunk.goland.PreviousCommentThread";
    static final String NEXT_ACTION_ID = "dev.hunk.goland.NextCommentThread";

    private final HunkReviewIndex.Direction direction;

    HunkThreadNavigationAction(HunkReviewIndex.Direction direction) {
        this.direction = direction;
    }

    static String actionId(HunkReviewIndex.Direction direction) {
        return direction == HunkReviewIndex.Direction.PREVIOUS
                ? PREVIOUS_ACTION_ID
                : NEXT_ACTION_ID;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var available = event.getProject() != null
                && (event.getData(CommonDataKeys.EDITOR) != null
                || event.getData(DiffDataKeys.CURRENT_EDITOR) != null);
        event.getPresentation().setEnabledAndVisible(available);
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
            case TargetResolution.Resolved resolved -> {
                var index = project.getService(HunkReviewGutterService.class)
                        .currentIndex(resolved.target());
                var destination = index.adjacentTo(resolved.target(), direction);
                if (destination == null) {
                    NotificationGroupManager.getInstance()
                            .getNotificationGroup("Hunk Session")
                            .createNotification(
                                    "No right-side Hunk comment threads are available in this review yet.",
                                    NotificationType.INFORMATION
                            )
                            .notify(project);
                    return;
                }
                HunkThreadNavigator.open(project, index, destination, resolved);
            }
            case TargetResolution.Unavailable(var reason) -> NotificationGroupManager.getInstance()
                    .getNotificationGroup("Hunk Session")
                    .createNotification(reason, NotificationType.WARNING)
                    .notify(project);
        }
    }
}
