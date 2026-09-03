package dev.hunk.goland.service;

import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HunkReviewRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-03T08:00:00Z"),
            ZoneOffset.UTC
    );
    private static final ReviewTarget TARGET = new ReviewTarget(
            Path.of("/workspace/repository"),
            "cmd/main.go",
            ReviewSide.NEW,
            4
    );

    @Test
    void restoresOnlyTheMissingMultiplicityOfReviewerComments() {
        var recovery = new HunkReviewRecovery(CLOCK);
        var duplicateOne = reviewer("user:old-1", "Same review point", 4);
        var duplicateTwo = reviewer("user:old-2", "Same review point", 4);
        recovery.checkpoint(TARGET, "exact-diff", List.of(duplicateOne, duplicateTwo));

        var plan = recovery.plan(
                TARGET,
                "exact-diff",
                List.of(reviewer("user:live", "Same review point", 4))
        );

        assertThat(plan.reviewerComments())
                .extracting(note -> note.note().id())
                .containsExactly("user:old-2");
    }

    @Test
    void keepsMissingAgentResponsesAsReadOnlyLocalHistory() {
        var recovery = new HunkReviewRecovery(CLOCK);
        recovery.checkpoint(TARGET, "exact-diff", List.of(
                reviewer("user:old", "Review this", 4),
                agent("agent:old", "Done", 4)
        ));

        var plan = recovery.plan(TARGET, "exact-diff", List.of());

        assertThat(plan.reviewerComments())
                .extracting(note -> note.note().body())
                .containsExactly("Review this");
        assertThat(plan.agentHistory()).singleElement().satisfies(note -> {
            assertThat(note.note().body()).isEqualTo("Done");
            assertThat(note.note().recovered()).isTrue();
            assertThat(note.note().editable()).isFalse();
        });
    }

    @Test
    void doesNotDuplicateAnAgentResponseThatReappearedInHunk() {
        var recovery = new HunkReviewRecovery(CLOCK);
        var cached = agent("agent:cached", "Done", 4);
        var live = agent("agent:new-id", "Done", 4);
        recovery.checkpoint(TARGET, "exact-diff", List.of(cached));

        var plan = recovery.plan(TARGET, "exact-diff", List.of(live));
        var merged = HunkReviewRecovery.mergeWithAgentHistory(List.of(live), plan.agentHistory());

        assertThat(plan.agentHistory()).isEmpty();
        assertThat(merged).containsExactly(live);
    }

    @Test
    void refusesToReplayNotesAgainstAChangedDiff() {
        var recovery = new HunkReviewRecovery(CLOCK);
        recovery.checkpoint(TARGET, "old-diff", List.of(
                reviewer("user:old", "Review this", 4),
                agent("agent:old", "Done", 4)
        ));

        var plan = recovery.plan(TARGET, "new-diff", List.of());

        assertThat(plan.reviewerComments()).isEmpty();
        assertThat(plan.agentHistory()).isEmpty();
        assertThat(plan.staleNoteCount()).isEqualTo(2);
    }

    @Test
    void anEmptyCheckpointPreventsADeletedCommentFromReturning() {
        var recovery = new HunkReviewRecovery(CLOCK);
        recovery.checkpoint(TARGET, "exact-diff", List.of(
                reviewer("user:deleted", "No longer relevant", 4)
        ));
        recovery.checkpoint(TARGET, "exact-diff", List.of());

        var afterRestart = new HunkReviewRecovery(CLOCK);
        afterRestart.loadState(recovery.getState());
        var plan = afterRestart.plan(TARGET, "exact-diff", List.of());

        assertThat(plan.reviewerComments()).isEmpty();
        assertThat(plan.agentHistory()).isEmpty();
        assertThat(plan.staleNoteCount()).isZero();
    }

    @Test
    void loadedStateCanRestoreTheExactReviewAfterAServiceRestart() {
        var beforeRestart = new HunkReviewRecovery(CLOCK);
        beforeRestart.checkpoint(TARGET, "exact-diff", List.of(
                reviewer("user:persisted", "Survive the restart", 4)
        ));

        var afterRestart = new HunkReviewRecovery(CLOCK);
        afterRestart.loadState(beforeRestart.getState());

        assertThat(afterRestart.plan(TARGET, "exact-diff", List.of()).reviewerComments())
                .extracting(note -> note.note().body())
                .containsExactly("Survive the restart");
    }

    @Test
    void boundsTheNumberAndAgeOfSavedReviews() {
        var recovery = new HunkReviewRecovery(CLOCK);
        for (var index = 0; index < HunkReviewRecovery.MAX_REVIEWS + 5; index++) {
            recovery.checkpoint(
                    TARGET,
                    "diff-" + index,
                    List.of(reviewer("user:" + index, "Comment " + index, 4))
            );
        }

        assertThat(recovery.getState().reviews).hasSize(HunkReviewRecovery.MAX_REVIEWS);

        var afterRetention = new HunkReviewRecovery(Clock.fixed(
                CLOCK.instant().plus(HunkReviewRecovery.RETENTION).plusSeconds(1),
                ZoneOffset.UTC
        ));
        afterRetention.loadState(recovery.getState());
        assertThat(afterRetention.getState().reviews).isEmpty();
    }

    @Test
    void boundsOversizedStateWhenItIsLoaded() {
        var state = new HunkReviewRecovery.Data();
        for (var index = 0; index < HunkReviewRecovery.MAX_REVIEWS + 5; index++) {
            state.reviews.add(HunkReviewRecovery.StoredReview.from(
                    TARGET,
                    "loaded-diff-" + index,
                    List.of(reviewer("loaded-user:" + index, "Loaded " + index, 4)),
                    CLOCK.millis() + index
            ));
        }

        var recovery = new HunkReviewRecovery(CLOCK);
        recovery.loadState(state);

        var loaded = recovery.getState().reviews;
        assertThat(loaded).hasSize(HunkReviewRecovery.MAX_REVIEWS);
        assertThat(loaded.getFirst().fingerprint).isEqualTo("loaded-diff-24");
        assertThat(loaded.getLast().fingerprint).isEqualTo("loaded-diff-5");
    }

    @Test
    void ignoresAMalformedCachedNoteWithoutBlockingTheLiveReview() {
        var state = new HunkReviewRecovery.Data();
        var review = HunkReviewRecovery.StoredReview.from(
                TARGET,
                "exact-diff",
                List.of(reviewer("valid", "Still usable", 4)),
                CLOCK.millis()
        );
        var malformed = new HunkReviewRecovery.StoredNote();
        malformed.id = "broken";
        review.notes.add(malformed);
        state.reviews.add(review);
        var recovery = new HunkReviewRecovery(CLOCK);
        recovery.loadState(state);

        assertThat(recovery.plan(TARGET, "exact-diff", List.of()).reviewerComments())
                .extracting(note -> note.note().body())
                .containsExactly("Still usable");
    }

    private static HunkReviewNote reviewer(String id, String body, int line) {
        return note(id, HunkNoteSource.USER, body, "user", line, true);
    }

    private static HunkReviewNote agent(String id, String body, int line) {
        return note(id, HunkNoteSource.AGENT, body, "receiver", line, false);
    }

    private static HunkReviewNote note(
            String id,
            HunkNoteSource source,
            String body,
            String author,
            int line,
            boolean editable
    ) {
        return new HunkReviewNote("cmd/main.go", new HunkSessionNote(
                id,
                source,
                body,
                author,
                "2026-09-03T07:00:00Z",
                ReviewSide.NEW,
                line,
                editable
        ));
    }
}
