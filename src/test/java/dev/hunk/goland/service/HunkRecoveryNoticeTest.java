package dev.hunk.goland.service;

import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HunkRecoveryNoticeTest {
    @Test
    void explainsNativeReviewerReplayAndLocalAgentHistorySeparately() {
        var plan = new HunkReviewRecovery.RecoveryPlan(
                List.of(note("reviewer", HunkNoteSource.USER)),
                List.of(note("agent", HunkNoteSource.AGENT)),
                0
        );

        assertThat(HunkSessionService.recoveryNotice(plan)).isEqualTo(
                "Restored 1 reviewer comment; showing 1 agent response from local history."
        );
    }

    @Test
    void explainsWhyStaleCommentsWereNotReplayed() {
        var plan = new HunkReviewRecovery.RecoveryPlan(List.of(), List.of(), 3);

        assertThat(HunkSessionService.recoveryNotice(plan)).isEqualTo(
                "3 locally saved review items were not restored because the comparison changed."
        );
    }

    @Test
    void keepsTheOriginalDisplayTimeOnTheNewNativeReviewerNote() {
        var live = note("new-native-id", HunkNoteSource.USER);

        var presented = HunkSessionService.presentRecoveredReviewerTimes(
                List.of(live),
                Map.of("new-native-id", "2026-09-01T09:00:00Z")
        );

        assertThat(presented).singleElement().satisfies(note -> {
            assertThat(note.note().id()).isEqualTo("new-native-id");
            assertThat(note.note().createdAt()).isEqualTo("2026-09-01T09:00:00Z");
            assertThat(note.note().editable()).isTrue();
            assertThat(note.note().recovered()).isFalse();
        });
    }

    private static HunkReviewNote note(String id, HunkNoteSource source) {
        return new HunkReviewNote("cmd/main.go", new HunkSessionNote(
                id,
                source,
                id,
                null,
                null,
                ReviewSide.NEW,
                4,
                source == HunkNoteSource.USER
        ));
    }
}
