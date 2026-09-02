package dev.hunk.goland.action;

import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HunkLineInlayTest {
    @Test
    void treatsMultipleSessionsForOneWorktreeAsRecoverableSelectionAmbiguity() {
        assertThat(HunkLineInlay.isAmbiguousSession(
                "Multiple active sessions match repoRoot /repo; specify sessionId instead."
        )).isTrue();
        assertThat(HunkLineInlay.isAmbiguousSession(
                "The active Hunk review does not contain main.go."
        )).isFalse();
    }

    @Test
    void rendersOldestFirstAndKeepsTheLatestCommentLast() {
        var latest = note("latest", "2026-09-01T10:02:00Z");
        var oldest = note("oldest", "2026-09-01T10:00:00Z");
        var middle = note("middle", "2026-09-01T10:01:00Z");

        assertThat(HunkLineInlay.chronologicalNotes(List.of(latest, oldest, middle)))
                .extracting(HunkSessionNote::id)
                .containsExactly("oldest", "middle", "latest");
    }

    @Test
    void leavesUndatedNotesStableAfterTimestampedConversation() {
        var firstUndated = note("first-undated", null);
        var dated = note("dated", "2026-09-01T10:00:00Z");
        var secondUndated = note("second-undated", "not-a-time");

        assertThat(HunkLineInlay.chronologicalNotes(List.of(firstUndated, dated, secondUndated)))
                .extracting(HunkSessionNote::id)
                .containsExactly("dated", "first-undated", "second-undated");
    }

    private static HunkSessionNote note(String id, String createdAt) {
        return new HunkSessionNote(
                id,
                HunkNoteSource.USER,
                id,
                "reviewer",
                createdAt,
                ReviewSide.NEW,
                4,
                true
        );
    }
}
