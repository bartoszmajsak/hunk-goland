package dev.hunk.goland.action;

import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HunkGutterSummaryTest {
    @Test
    void distinguishesReviewAgentAndMixedThreads() {
        var review = note("review", HunkNoteSource.USER);
        var agent = note("agent", HunkNoteSource.AGENT);

        assertThat(HunkGutterSummary.from(List.of(review)).kind())
                .isEqualTo(HunkGutterSummary.Kind.REVIEW);
        assertThat(HunkGutterSummary.from(List.of(agent)).kind())
                .isEqualTo(HunkGutterSummary.Kind.AGENT);

        var mixed = HunkGutterSummary.from(List.of(review, agent));
        assertThat(mixed.kind()).isEqualTo(HunkGutterSummary.Kind.MIXED);
        assertThat(mixed.tooltip()).isEqualTo(
                "Hunk: 1 review comment and 1 agent response — click to open the inline thread"
        );
    }

    private static HunkSessionNote note(String id, HunkNoteSource source) {
        return new HunkSessionNote(
                id,
                source,
                "body",
                "author",
                null,
                ReviewSide.NEW,
                4,
                source == HunkNoteSource.USER
        );
    }
}
