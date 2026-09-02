package dev.hunk.goland.model;

public enum ReviewSide {
    OLD("old"),
    NEW("new");

    private final String wireName;

    ReviewSide(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ReviewSide fromWireName(String value) {
        return switch (value) {
            case "old" -> OLD;
            case "new" -> NEW;
            case null, default -> null;
        };
    }
}
