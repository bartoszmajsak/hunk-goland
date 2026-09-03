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
        boolean editable,
        boolean recovered
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
        this(id, source, body, author, createdAt, side, line, editable, false);
    }

    public HunkSessionNote {
        id = Objects.requireNonNull(id, "id");
        source = Objects.requireNonNull(source, "source");
        body = Objects.requireNonNull(body, "body");
        side = Objects.requireNonNull(side, "side");
        if (line < 1) {
            throw new IllegalArgumentException("line must be 1-based");
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
                false,
                true
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
                editable,
                recovered
        );
    }
}
