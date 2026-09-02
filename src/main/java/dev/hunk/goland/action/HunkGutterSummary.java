package dev.hunk.goland.action;

import dev.hunk.goland.model.HunkSessionNote;

import java.util.List;

/** Small, stable view model for one annotated editor line. */
record HunkGutterSummary(int reviewCount, int agentCount) {
    HunkGutterSummary {
        if (reviewCount < 0 || agentCount < 0 || reviewCount + agentCount == 0) {
            throw new IllegalArgumentException("a gutter marker needs at least one note");
        }
    }

    static HunkGutterSummary from(List<HunkSessionNote> notes) {
        var reviewCount = Math.toIntExact(notes.stream().filter(HunkSessionNote::isReviewerComment).count());
        return new HunkGutterSummary(reviewCount, notes.size() - reviewCount);
    }

    Kind kind() {
        if (reviewCount == 0) {
            return Kind.AGENT;
        }
        return agentCount == 0 ? Kind.REVIEW : Kind.MIXED;
    }

    String tooltip() {
        var notes = switch (kind()) {
            case REVIEW -> count(reviewCount, "review comment", "review comments");
            case AGENT -> count(agentCount, "agent response", "agent responses");
            case MIXED -> count(reviewCount, "review comment", "review comments")
                    + " and " + count(agentCount, "agent response", "agent responses");
        };
        return "Hunk: " + notes + " — click to open the inline thread";
    }

    private static String count(int value, String singular, String plural) {
        return value + " " + (value == 1 ? singular : plural);
    }

    enum Kind {
        REVIEW,
        AGENT,
        MIXED
    }
}
