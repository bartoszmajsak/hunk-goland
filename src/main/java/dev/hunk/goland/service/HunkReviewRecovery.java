package dev.hunk.goland.service;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkReviewInput;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Non-roaming, bounded recovery cache for reviews owned by this IDE. */
@Service(Service.Level.PROJECT)
@State(
        name = "dev.hunk.goland.HunkReviewRecovery",
        storages = @Storage(value = StoragePathMacros.CACHE_FILE, roamingType = RoamingType.DISABLED)
)
public final class HunkReviewRecovery implements PersistentStateComponent<HunkReviewRecovery.Data> {
    static final int MAX_REVIEWS = 20;
    static final Duration RETENTION = Duration.ofDays(30);

    private final Clock clock;
    private Data data = new Data();

    public HunkReviewRecovery() {
        this(Clock.systemUTC());
    }

    HunkReviewRecovery(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized @NotNull Data getState() {
        prune();
        return data.copy();
    }

    @Override
    public synchronized void loadState(@NotNull Data state) {
        data = state.copy();
        if (data.reviews == null) {
            data.reviews = new ArrayList<>();
        }
        prune();
    }

    synchronized RecoveryPlan plan(
            ReviewTarget target,
            String fingerprint,
            List<HunkReviewNote> liveNotes
    ) {
        return plan(target, fingerprint, liveNotes, Map.of());
    }

    synchronized RecoveryPlan plan(
            ReviewTarget target,
            String fingerprint,
            List<HunkReviewNote> liveNotes,
            Map<String, String> restoredIds
    ) {
        prune();
        var matching = data.reviews.stream()
                .filter(review -> review.matches(target))
                .filter(review -> fingerprint.equals(review.fingerprint))
                .max(Comparator.comparingLong(review -> review.savedAtEpochMillis));
        if (matching.isEmpty()) {
            var staleNotes = data.reviews.stream()
                    .filter(review -> review.matches(target))
                    .mapToInt(review -> review.notes == null ? 0 : review.notes.size())
                    .sum();
            return new RecoveryPlan(List.of(), List.of(), staleNotes);
        }

        var remainingLive = semanticCounts(liveNotes);
        var reviewerComments = new ArrayList<HunkReviewNote>();
        var localHistory = new ArrayList<HunkReviewNote>();
        for (var stored : matching.get().safeNotes()) {
            final HunkReviewNote note;
            try {
                var cached = stored.toModel(false);
                var parentId = cached.note().parentId();
                note = new HunkReviewNote(cached.filePath(), cached.note().withParent(
                        parentId == null ? null : restoredIds.getOrDefault(parentId, parentId)
                ));
            } catch (RuntimeException ignored) {
                // A partially written or manually edited cache must never block the live review.
                continue;
            }
            var key = SemanticNote.from(note);
            var count = remainingLive.getOrDefault(key, 0);
            if (count > 0) {
                remainingLive.put(key, count - 1);
            } else if (note.note().source() == HunkNoteSource.USER && note.note().parentId() == null) {
                reviewerComments.add(note);
            } else {
                localHistory.add(new HunkReviewNote(
                        note.filePath(),
                        note.note().asRecoveredHistory()
                ));
            }
        }
        return new RecoveryPlan(
                List.copyOf(reviewerComments),
                List.copyOf(localHistory),
                0
        );
    }

    synchronized void checkpoint(
            ReviewTarget target,
            String fingerprint,
            List<HunkReviewNote> notes
    ) {
        prune();
        data.reviews.removeIf(review -> review.matches(target)
                && fingerprint.equals(review.fingerprint));
        if (!notes.isEmpty()) {
            data.reviews.add(StoredReview.from(target, fingerprint, notes, clock.millis()));
        }
        limitReviews();
    }

    private void limitReviews() {
        data.reviews.sort(Comparator.comparingLong(
                (StoredReview review) -> review.savedAtEpochMillis
        ).reversed());
        if (data.reviews.size() > MAX_REVIEWS) {
            data.reviews.subList(MAX_REVIEWS, data.reviews.size()).clear();
        }
    }

    static List<HunkReviewNote> mergeWithLocalHistory(
            List<HunkReviewNote> liveNotes,
            List<HunkReviewNote> localHistory
    ) {
        var remainingLive = semanticCounts(liveNotes);
        var result = new ArrayList<>(liveNotes);
        for (var recovered : localHistory) {
            var key = SemanticNote.from(recovered);
            var count = remainingLive.getOrDefault(key, 0);
            if (count > 0) {
                remainingLive.put(key, count - 1);
            } else {
                result.add(recovered);
            }
        }
        return List.copyOf(result);
    }

    private void prune() {
        var cutoff = clock.instant().minus(RETENTION).toEpochMilli();
        data.reviews.removeIf(review -> review == null || review.savedAtEpochMillis < cutoff);
        limitReviews();
    }

    private static Map<SemanticNote, Integer> semanticCounts(List<HunkReviewNote> notes) {
        var counts = new HashMap<SemanticNote, Integer>();
        notes.forEach(note -> counts.merge(SemanticNote.from(note), 1, Integer::sum));
        return counts;
    }

    record RecoveryPlan(
            List<HunkReviewNote> reviewerComments,
            List<HunkReviewNote> localHistory,
            int staleNoteCount
    ) {
    }

    private record SemanticNote(
            String filePath,
            HunkNoteSource source,
            String body,
            String author,
            ReviewSide side,
            int line,
            int endLine,
            String parentId
    ) {
        static SemanticNote from(HunkReviewNote value) {
            var note = value.note();
            return new SemanticNote(
                    value.filePath(),
                    note.source(),
                    note.body(),
                    note.source() == HunkNoteSource.AGENT ? note.author() : null,
                    note.side(),
                    note.line(),
                    note.endLine(),
                    note.parentId()
            );
        }
    }

    public static final class Data {
        public int schemaVersion = 1;
        public List<StoredReview> reviews = new ArrayList<>();

        Data copy() {
            var result = new Data();
            result.schemaVersion = schemaVersion;
            result.reviews = reviews == null
                    ? new ArrayList<>()
                    : reviews.stream()
                            .filter(java.util.Objects::nonNull)
                            .map(StoredReview::copy)
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return result;
        }
    }

    public static final class StoredReview {
        public String repositoryRoot;
        public String inputKind;
        public String beforeRevision;
        public String afterRevision;
        public String fingerprint;
        public long savedAtEpochMillis;
        public List<StoredNote> notes = new ArrayList<>();

        public StoredReview() {
        }

        static StoredReview from(
                ReviewTarget target,
                String fingerprint,
                List<HunkReviewNote> notes,
                long savedAtEpochMillis
        ) {
            var result = new StoredReview();
            result.repositoryRoot = target.repositoryRoot().toString();
            result.fingerprint = fingerprint;
            result.savedAtEpochMillis = savedAtEpochMillis;
            switch (target.reviewInput()) {
                case HunkReviewInput.WorkingTree ignored -> result.inputKind = "working-tree";
                case HunkReviewInput.RevisionRange range -> {
                    result.inputKind = "revision-range";
                    result.beforeRevision = range.beforeRevision();
                    result.afterRevision = range.afterRevision();
                }
                case HunkReviewInput.RevisionToWorkingTree comparison -> {
                    result.inputKind = "revision-to-working-tree";
                    result.beforeRevision = comparison.beforeRevision();
                }
            }
            result.notes = notes.stream().map(StoredNote::from).collect(
                    java.util.stream.Collectors.toCollection(ArrayList::new)
            );
            return result;
        }

        boolean matches(ReviewTarget target) {
            if (!target.repositoryRoot().toString().equals(repositoryRoot)) {
                return false;
            }
            return switch (target.reviewInput()) {
                case HunkReviewInput.WorkingTree ignored -> "working-tree".equals(inputKind);
                case HunkReviewInput.RevisionRange range -> "revision-range".equals(inputKind)
                        && range.beforeRevision().equals(beforeRevision)
                        && range.afterRevision().equals(afterRevision);
                case HunkReviewInput.RevisionToWorkingTree comparison ->
                        "revision-to-working-tree".equals(inputKind)
                                && comparison.beforeRevision().equals(beforeRevision);
            };
        }

        List<StoredNote> safeNotes() {
            return notes == null ? List.of() : notes;
        }

        StoredReview copy() {
            var result = new StoredReview();
            result.repositoryRoot = repositoryRoot;
            result.inputKind = inputKind;
            result.beforeRevision = beforeRevision;
            result.afterRevision = afterRevision;
            result.fingerprint = fingerprint;
            result.savedAtEpochMillis = savedAtEpochMillis;
            result.notes = safeNotes().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(StoredNote::copy)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return result;
        }
    }

    public static final class StoredNote {
        public String id;
        public String filePath;
        public String source;
        public String body;
        public String author;
        public String createdAt;
        public String side;
        public int line;
        public int endLine;
        public String parentId;

        public StoredNote() {
        }

        static StoredNote from(HunkReviewNote value) {
            var result = new StoredNote();
            var note = value.note();
            result.id = note.id();
            result.filePath = value.filePath();
            result.source = note.source() == HunkNoteSource.USER ? "user" : "agent";
            result.body = note.body();
            result.author = note.author();
            result.createdAt = note.createdAt();
            result.side = note.side().wireName();
            result.line = note.line();
            result.endLine = note.endLine();
            result.parentId = note.parentId();
            return result;
        }

        HunkReviewNote toModel(boolean recovered) {
            var noteSource = HunkNoteSource.fromWireName(source);
            var noteSide = ReviewSide.fromWireName(side);
            if (noteSide == null) {
                throw new IllegalStateException("Stored Hunk note has an invalid review side.");
            }
            return new HunkReviewNote(filePath, new HunkSessionNote(
                    id,
                    noteSource,
                    body,
                    author,
                    createdAt,
                    noteSide,
                    line,
                    endLine == 0 ? line : endLine,
                    !recovered && noteSource == HunkNoteSource.USER,
                    recovered,
                    parentId
            ));
        }

        StoredNote copy() {
            var result = new StoredNote();
            result.id = id;
            result.filePath = filePath;
            result.source = source;
            result.body = body;
            result.author = author;
            result.createdAt = createdAt;
            result.side = side;
            result.line = line;
            result.endLine = endLine;
            result.parentId = parentId;
            return result;
        }
    }
}
