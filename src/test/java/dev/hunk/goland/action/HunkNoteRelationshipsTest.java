package dev.hunk.goland.action;

import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HunkNoteRelationshipsTest {
    @Test
    void labelsRepliesUsingNativeParentIdentityRatherThanMatchingBodyOrLine() {
        var parent = note("parent", "Ari", null);
        var other = note("other", "Sam", null);
        var reply = note("reply", "Agent", "parent");
        assertThat(HunkNoteRelationships.replyLabel(reply, List.of(parent, other, reply),
                HunkSessionNote::author)).isEqualTo("Reply to Ari");
        assertThat(HunkNoteRelationships.replyLabel(parent, List.of(parent), HunkSessionNote::author)).isNull();
    }

    @Test
    void explicitlyLabelsMissingParentsWithoutGuessingAReplacement() {
        var reply = note("reply", "Agent", "missing");
        assertThat(HunkNoteRelationships.replyLabel(reply, List.of(note("other", "Ari", null)),
                HunkSessionNote::author)).isEqualTo("Reply · parent unavailable");
    }

    @Test
    void onlyLiveRepliesPreventNativeDeletion() {
        var parent = note("parent", "Ari", null);
        var reply = note("reply", "Agent", "parent");
        assertThat(HunkNoteRelationships.hasLiveReplies(parent, List.of(parent, reply))).isTrue();
        assertThat(HunkNoteRelationships.hasLiveReplies(parent,
                List.of(parent, reply.asRecoveredHistory()))).isFalse();
    }

    private static HunkSessionNote note(String id, String author, String parent) {
        return new HunkSessionNote(id, HunkNoteSource.AGENT, "Same body", author, null,
                ReviewSide.NEW, 4, 4, false, false, parent);
    }
}
