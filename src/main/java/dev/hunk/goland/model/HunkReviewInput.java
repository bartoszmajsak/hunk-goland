package dev.hunk.goland.model;

import java.util.List;
import java.util.Objects;

/** The exact diff input that Hunk must publish for an IDE review target. */
public sealed interface HunkReviewInput {
    List<String> command();

    String actionLabel();

    String commandDisplay();

    record WorkingTree() implements HunkReviewInput {
        @Override
        public List<String> command() {
            return List.of("hunk", "diff", "--watch");
        }

        @Override
        public String actionLabel() {
            return "Start IDE-managed review";
        }

        @Override
        public String commandDisplay() {
            return "hunk diff --watch";
        }
    }

    record RevisionRange(String beforeRevision, String afterRevision) implements HunkReviewInput {
        public RevisionRange {
            beforeRevision = requireRevision(beforeRevision, "beforeRevision");
            afterRevision = requireRevision(afterRevision, "afterRevision");
        }

        @Override
        public List<String> command() {
            return List.of("hunk", "diff", range(), "--watch");
        }

        @Override
        public String actionLabel() {
            return "Start matching IDE-managed review";
        }

        @Override
        public String commandDisplay() {
            return "hunk diff " + abbreviatedRange() + " --watch";
        }

        public String range() {
            return beforeRevision + ".." + afterRevision;
        }

        private String abbreviatedRange() {
            return abbreviate(beforeRevision) + ".." + abbreviate(afterRevision);
        }
    }

    /** A concrete base revision compared with the current index and working tree. */
    record RevisionToWorkingTree(String beforeRevision) implements HunkReviewInput {
        public RevisionToWorkingTree {
            beforeRevision = requireRevision(beforeRevision, "beforeRevision");
        }

        @Override
        public List<String> command() {
            return List.of("hunk", "diff", beforeRevision, "--watch");
        }

        @Override
        public String actionLabel() {
            return "Start matching IDE-managed review";
        }

        @Override
        public String commandDisplay() {
            return "hunk diff " + abbreviate(beforeRevision) + " --watch";
        }
    }

    static HunkReviewInput workingTree() {
        return new WorkingTree();
    }

    private static String requireRevision(String value, String name) {
        var revision = Objects.requireNonNull(value, name).trim();
        if (revision.isEmpty() || revision.startsWith("-")) {
            throw new IllegalArgumentException(name + " must be a concrete revision");
        }
        return revision;
    }

    private static String abbreviate(String revision) {
        return revision.length() <= 10 ? revision : revision.substring(0, 10);
    }
}
