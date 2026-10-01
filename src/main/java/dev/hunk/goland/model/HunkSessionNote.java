package dev.hunk.goland.model;

import java.util.Objects;

/** One note projected by Hunk for display in an IDE review thread. */
public record HunkSessionNote(
        String id,
        HunkNoteSource source,
        String body,
        String author,
        String createdAt,
        ReviewSide side,
        int line,
        int endLine,
        boolean editable,
        boolean recovered,
        String parentId
) {
    public HunkSessionNote(
            String id,
            HunkNoteSource source,
            String body,
            String author,
            String createdAt,
            ReviewSide side,
            int line,
            boolean editable
    ) {
        this(id, source, body, author, createdAt, side, line, line, editable, false, null);
    }

    public HunkSessionNote(String id, HunkNoteSource source, String body, String author,
                           String createdAt, ReviewSide side, int line, boolean editable,
                           boolean recovered) {
        this(id, source, body, author, createdAt, side, line, line, editable, recovered, null);
    }

    public HunkSessionNote(String id, HunkNoteSource source, String body, String author,
                           String createdAt, ReviewSide side, int line, int endLine,
                           boolean editable, boolean recovered) {
        this(id, source, body, author, createdAt, side, line, endLine, editable, recovered, null);
    }

    public HunkSessionNote {
        id = Objects.requireNonNull(id, "id");
        source = Objects.requireNonNull(source, "source");
        body = Objects.requireNonNull(body, "body");
        side = Objects.requireNonNull(side, "side");
        if (line < 1) {
            throw new IllegalArgumentException("line must be 1-based");
        }
        if (endLine < line) {
            throw new IllegalArgumentException("endLine must not precede line");
        }
        if (parentId != null && (parentId.isBlank() || parentId.equals(id))) {
            throw new IllegalArgumentException("parentId must reference another note");
        }
    }

    public boolean isReviewerComment() {
        return source == HunkNoteSource.USER;
    }

    public HunkSessionNote asRecoveredHistory() {
        return new HunkSessionNote(
                id,
                source,
                body,
                author,
                createdAt,
                side,
                line,
                endLine,
                false,
                true,
                parentId
        );
    }

    public HunkSessionNote withDisplayedCreationTime(String displayedCreatedAt) {
        return new HunkSessionNote(
                id,
                source,
                body,
                author,
                displayedCreatedAt,
                side,
                line,
                endLine,
                editable,
                recovered,
                parentId
        );
    }

    public HunkSessionNote withParent(String parent) {
        return new HunkSessionNote(id, source, body, author, createdAt, side, line, endLine,
                editable, recovered, parent);
    }
}
