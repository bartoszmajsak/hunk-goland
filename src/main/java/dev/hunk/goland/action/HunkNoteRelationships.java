package dev.hunk.goland.action;

import dev.hunk.goland.model.HunkSessionNote;

import java.util.List;
import java.util.function.Function;

/** Presentation rules for native parent identities, without inferring relationships from text. */
final class HunkNoteRelationships {
    private HunkNoteRelationships() {
    }

    static String replyLabel(HunkSessionNote note, List<HunkSessionNote> notes,
                             Function<HunkSessionNote, String> displayAuthor) {
        if (note.parentId() == null) {
            return null;
        }
        return notes.stream()
                .filter(candidate -> candidate.id().equals(note.parentId()))
                .findFirst()
                .map(parent -> "Reply to " + displayAuthor.apply(parent))
                .orElse("Reply · parent unavailable");
    }

    static boolean hasLiveReplies(HunkSessionNote note, List<HunkSessionNote> notes) {
        return notes.stream().anyMatch(candidate -> !candidate.recovered()
                && note.id().equals(candidate.parentId()));
    }
}
