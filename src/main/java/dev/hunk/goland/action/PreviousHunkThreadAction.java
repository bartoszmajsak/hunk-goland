package dev.hunk.goland.action;

public final class PreviousHunkThreadAction extends HunkThreadNavigationAction {
    public PreviousHunkThreadAction() {
        super(HunkReviewIndex.Direction.PREVIOUS);
    }
}
