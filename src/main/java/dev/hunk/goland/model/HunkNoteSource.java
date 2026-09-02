package dev.hunk.goland.model;

/** Provenance assigned by Hunk, independent of the free-form author label. */
public enum HunkNoteSource {
    USER,
    AGENT;

    public static HunkNoteSource fromWireName(String value) {
        return "user".equals(value) ? USER : AGENT;
    }
}
