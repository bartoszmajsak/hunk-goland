package dev.hunk.goland.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.hunk.goland.model.HunkNoteSource;
import dev.hunk.goland.model.HunkReviewInput;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;
import dev.hunk.goland.settings.HunkSettings;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class HunkSessionService {
    private static final Logger LOG = Logger.getInstance(HunkSessionService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final Duration PERSISTENCE_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration PERSISTENCE_POLL_INTERVAL = Duration.ofMillis(100);
    private static final String READ_REVIEW = "review";
    private static final String NAVIGATE = "navigate";
    private static final String REMOVE_COMMENT = "comment-rm";
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    private final HunkSessionTransport transport;
    private final HunkReviewLauncher launcher;
    private final HunkReviewRecovery recovery;
    private final Object recoveryLock = new Object();
    private final Map<RecoveredSessionKey, RecoveredSession> recoveredSessions = new HashMap<>();
    private final Map<RecoveryContextKey, String> recoveryNotices = new HashMap<>();
    private final Map<String, VerifiedFingerprint> verifiedFingerprints = new HashMap<>();

    public HunkSessionService(Project project) {
        transport = project.getService(HunkSessionTransport.class);
        launcher = project.getService(HunkReviewLauncher.class);
        recovery = project.getService(HunkReviewRecovery.class);
    }

    public void addReviewComment(ReviewTarget target, String body)
            throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        var reviewBefore = readReview(origin, target, true);
        submitReviewerComment(origin, target, body, reviewBefore, "review-comment-submit");
        // Persist the daemon-confirmed state before reporting success to the editor. This closes
        // the small window where a plugin update could otherwise lose the just-added comment.
        readReview(origin, target, true);
    }

    private HunkReviewNote submitReviewerComment(
            URI origin,
            ReviewTarget target,
            String body,
            JsonObject reviewBefore,
            String event
    ) throws HunkSessionException {
        var normalizedBody = body.replace("\r\n", "\n").replace('\r', '\n');
        validateLineTarget(reviewBefore, target);
        if (!launcher.owns(target)) {
            throw unmanagedReview();
        }
        var existingNoteIds = reviewNotes(reviewBefore).stream()
                .map(HunkReviewNote::note)
                .map(HunkSessionNote::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        LOG.info("event=" + event + "-start"
                + " sessionId=" + launcher.sessionId(target).orElse("unknown")
                + " target=" + target.displayName()
                + " existingNotes=" + existingNoteIds.size()
                + " bodyChars=" + body.length());
        var startedAt = System.nanoTime();
        var relay = launcher.addReviewerComment(target, body, () -> navigate(origin, target));
        try {
            var persisted = awaitPersistedReviewerNote(
                    origin,
                    target,
                    normalizedBody,
                    existingNoteIds
            );
            LOG.info("event=" + event + "-succeeded"
                    + " sessionId=" + launcher.sessionId(target).orElse("unknown")
                    + " target=" + target.displayName()
                    + " noteId=" + persisted.note().id()
                    + " relayAttempts=" + relay.attempts()
                    + " terminalConfirmed=" + relay.terminalConfirmed()
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAt
                    ));
            return persisted;
        } catch (HunkSessionException error) {
            launcher.recoverReviewerComposer(target, "daemon-persistence-unconfirmed");
            LOG.warn("event=" + event + "-failed"
                    + " sessionId=" + launcher.sessionId(target).orElse("unknown")
                    + " target=" + target.displayName()
                    + " relayAttempts=" + relay.attempts()
                    + " terminalConfirmed=" + relay.terminalConfirmed()
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAt
                    ), error);
            throw error;
        }
    }

    private void navigate(URI origin, ReviewTarget target) throws HunkSessionException {
        requireCapability(origin, NAVIGATE);
        var navigate = new JsonObject();
        navigate.addProperty("action", NAVIGATE);
        navigate.add("selector", selector(target));
        navigate.addProperty("filePath", target.filePath());
        navigate.addProperty("side", target.side().wireName());
        navigate.addProperty("line", target.line());
        responseObject(post(origin.resolve("/session-api"), navigate), NAVIGATE);
    }

    private HunkReviewNote awaitPersistedReviewerNote(
            URI origin,
            ReviewTarget target,
            String body,
            Set<String> existingNoteIds
    ) throws HunkSessionException {
        var result = new CompletableFuture<HunkReviewNote>();
        var lastReadError = new AtomicReference<HunkSessionException>();
        var attempts = new AtomicInteger();
        var startedAt = System.nanoTime();
        var deadline = System.nanoTime() + PERSISTENCE_TIMEOUT.toNanos();
        pollPersistedReviewerNote(
                origin,
                target,
                body,
                Set.copyOf(existingNoteIds),
                attempts,
                startedAt,
                deadline,
                lastReadError,
                result
        );
        try {
            return result.get(
                    PERSISTENCE_TIMEOUT.plus(TIMEOUT).toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.cancel(false);
            throw new HunkSessionException(
                    "Interrupted while verifying the reviewer comment in Hunk.",
                    error
            );
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HunkSessionException hunkError) {
                throw hunkError;
            }
            throw new HunkSessionException(
                    "Could not verify the reviewer comment in Hunk.",
                    error.getCause()
            );
        } catch (TimeoutException error) {
            result.cancel(false);
            throw persistenceTimeout(target, lastReadError.get());
        }
    }

    private void pollPersistedReviewerNote(
            URI origin,
            ReviewTarget target,
            String body,
            Set<String> existingNoteIds,
            AtomicInteger attempts,
            long startedAt,
            long deadline,
            AtomicReference<HunkSessionException> lastReadError,
            CompletableFuture<HunkReviewNote> result
    ) {
        if (result.isDone()) {
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                var attempt = attempts.incrementAndGet();
                var review = requestReview(origin, target);
                var notes = reviewNotes(review);
                var match = newReviewerNote(
                        review,
                        target,
                        body,
                        existingNoteIds
                );
                var candidates = notes.stream()
                        .filter(note -> !existingNoteIds.contains(note.note().id()))
                        .filter(note -> note.filePath().equals(target.filePath()))
                        .filter(note -> note.note().source() == HunkNoteSource.USER)
                        .filter(note -> note.note().side() == target.side())
                        .filter(note -> note.note().line() == target.line())
                        .count();
                HunkDiagnostics.info(LOG, "persistence-poll", () -> "sessionId="
                        + launcher.sessionId(target).orElse("unknown")
                        + " target=" + target.displayName()
                        + " attempt=" + attempt
                        + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startedAt
                        )
                        + " reviewNotes=" + notes.size()
                        + " newLocationCandidates=" + candidates
                        + " exactMatch=" + match.isPresent());
                if (match.isPresent()) {
                    result.complete(match.get());
                    return;
                }
            } catch (HunkSessionException error) {
                lastReadError.set(error);
                var attempt = attempts.get();
                HunkDiagnostics.warning(LOG, "persistence-poll-failed", () -> "sessionId="
                        + launcher.sessionId(target).orElse("unknown")
                        + " target=" + target.displayName()
                        + " attempt=" + attempt
                        + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startedAt
                        )
                        + " errorType=" + error.getClass().getSimpleName());
            }

            if (System.nanoTime() >= deadline) {
                result.completeExceptionally(persistenceTimeout(target, lastReadError.get()));
                return;
            }
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    () -> pollPersistedReviewerNote(
                            origin,
                            target,
                            body,
                            existingNoteIds,
                            attempts,
                            startedAt,
                            deadline,
                            lastReadError,
                            result
                    ),
                    PERSISTENCE_POLL_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        });
    }

    static Optional<HunkReviewNote> newReviewerNote(
            JsonObject review,
            ReviewTarget target,
            String body,
            Set<String> existingNoteIds
    ) {
        return reviewNotes(review).stream()
                .filter(note -> !existingNoteIds.contains(note.note().id()))
                .filter(note -> note.filePath().equals(target.filePath()))
                .filter(note -> note.note().source() == HunkNoteSource.USER)
                .filter(note -> note.note().side() == target.side())
                .filter(note -> note.note().line() == target.line())
                .filter(note -> note.note().endLine() == target.endLine())
                .filter(note -> note.note().body().equals(body))
                .findFirst();
    }

    private static HunkSessionException persistenceTimeout(
            ReviewTarget target,
            HunkSessionException lastReadError
    ) {
        return new HunkSessionException(
                "Hunk did not publish the reviewer comment on " + target.displayName()
                        + " within " + PERSISTENCE_TIMEOUT.toSeconds()
                        + " seconds. The composer was recovered; retry without retyping your comment.",
                lastReadError
        );
    }

    public void removeReviewComment(ReviewTarget target, String noteId)
            throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        requireCapability(origin, REMOVE_COMMENT);
        var payload = new JsonObject();
        payload.addProperty("action", REMOVE_COMMENT);
        payload.add("selector", selector(target));
        payload.addProperty("commentId", noteId);
        requireRemoved(responseObject(post(origin.resolve("/session-api"), payload), REMOVE_COMMENT));
        readReview(origin, target, true);
    }

    /** Verifies that a line has a native address in the active Hunk review. */
    public void validateCommentTarget(ReviewTarget target) throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        validateLineTarget(readReview(origin, target), target);
        if (!launcher.owns(target)) {
            throw unmanagedReview();
        }
    }

    public List<HunkSessionNote> listNotes(ReviewTarget target) throws HunkSessionException {
        return listFileNotes(target).stream()
                .filter(note -> note.line() == target.line())
                .toList();
    }

    /** Returns every native Hunk note for the target file and review side. */
    public List<HunkSessionNote> listFileNotes(ReviewTarget target) throws HunkSessionException {
        return listReviewNotes(target).stream()
                .filter(note -> target.filePath().equals(note.filePath()))
                .map(HunkReviewNote::note)
                .filter(note -> note.side() == target.side())
                .toList();
    }

    /** Returns every native note in the selected Hunk review, including its file identity. */
    public List<HunkReviewNote> listReviewNotes(ReviewTarget target) throws HunkSessionException {
        var settings = HunkSettings.getInstance().getState();
        var origin = origin(settings.host, settings.port);
        return reviewNotes(readReview(origin, target));
    }

    /** A non-blocking explanation of the most recent recovery decision for this comparison. */
    public Optional<String> recoveryNotice(ReviewTarget target) {
        synchronized (recoveryLock) {
            return Optional.ofNullable(recoveryNotices.get(RecoveryContextKey.from(target)));
        }
    }

    static List<HunkReviewNote> reviewNotes(JsonObject review) {
        var comments = review.getAsJsonArray("reviewNotes");
        if (comments == null) {
            return List.of();
        }

        var fileOrder = fileOrder(review.getAsJsonArray("files"));
        return comments.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .map(HunkSessionService::parseReviewNote)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(note -> fileOrder.getOrDefault(
                        note.filePath(),
                        Integer.MAX_VALUE
                )))
                .toList();
    }

    private static Map<String, Integer> fileOrder(JsonArray files) {
        if (files == null) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < files.size(); index++) {
            var value = files.get(index);
            if (!value.isJsonObject()) {
                continue;
            }
            var file = value.getAsJsonObject();
            var rank = index;
            Optional.ofNullable(string(file, "path")).ifPresent(path -> result.putIfAbsent(path, rank));
            Optional.ofNullable(string(file, "previousPath"))
                    .ifPresent(path -> result.putIfAbsent(path, rank));
        }
        return result;
    }

    static Optional<HunkReviewNote> parseReviewNote(JsonObject value) {
        var filePath = string(value, "filePath");
        if (filePath == null) {
            return Optional.empty();
        }
        try {
            return parseNote(value).map(note -> new HunkReviewNote(filePath, note));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private JsonObject readReview(URI origin, ReviewTarget target) throws HunkSessionException {
        return readReview(origin, target, false);
    }

    private JsonObject readReview(
            URI origin,
            ReviewTarget target,
            boolean verifyFingerprint
    ) throws HunkSessionException {
        requireCapability(origin, READ_REVIEW);
        return reconcileReview(origin, target, requestReview(origin, target), verifyFingerprint);
    }

    private JsonObject reconcileReview(
            URI origin,
            ReviewTarget target,
            JsonObject liveReview,
            boolean verifyFingerprint
    ) throws HunkSessionException {
        if (!launcher.owns(target)) {
            return liveReview;
        }
        var sessionId = string(liveReview, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return liveReview;
        }

        synchronized (recoveryLock) {
            var nativeNotes = reviewNotes(liveReview);
            var shape = HunkReviewFingerprint.shape(target.reviewInput(), liveReview);
            var verified = verifiedFingerprints.get(sessionId);
            if (verifyFingerprint
                    || verified == null
                    || !verified.shape().equals(shape)
                    || !verified.nativeNotes().equals(nativeNotes)) {
                var fingerprint = HunkReviewFingerprint.compute(
                        target.repositoryRoot(),
                        target.reviewInput(),
                        liveReview
                );
                if (fingerprint.isEmpty()) {
                    HunkDiagnostics.warning(LOG, "review-recovery-skipped", () -> "sessionId=" + sessionId
                            + " target=" + target.displayName()
                            + " reason=fingerprint-unavailable");
                    recoveryNotices.put(
                            RecoveryContextKey.from(target),
                            "Locally saved review items were not restored because the comparison "
                                    + "identity could not be verified."
                    );
                    return liveReview;
                }
                verified = new VerifiedFingerprint(shape, fingerprint.get(), nativeNotes);
                verifiedFingerprints.put(sessionId, verified);
            }

            var exactFingerprint = verified.fingerprint();
            var key = new RecoveredSessionKey(sessionId, exactFingerprint);
            var remembered = recoveredSessions.get(key);
            var current = liveReview;
            if (remembered == null) {
                var plan = recovery.plan(target, exactFingerprint, nativeNotes);
                var reviewerCreationTimes = new HashMap<String, String>();
                HunkDiagnostics.info(LOG, "review-recovery-plan", () -> "sessionId=" + sessionId
                        + " target=" + target.displayName()
                        + " reviewerComments=" + plan.reviewerComments().size()
                        + " agentHistory=" + plan.agentHistory().size()
                        + " staleNotes=" + plan.staleNoteCount());
                for (var reviewNote : plan.reviewerComments()) {
                    var note = reviewNote.note();
                    var replayTarget = new ReviewTarget(
                            target.repositoryRoot(),
                            reviewNote.filePath(),
                            note.side(),
                            note.line(),
                            note.endLine(),
                            target.reviewInput()
                    );
                    var persisted = submitReviewerComment(
                            origin,
                            replayTarget,
                            note.body(),
                            current,
                            "review-comment-recovery"
                    );
                    if (note.createdAt() != null) {
                        reviewerCreationTimes.put(persisted.note().id(), note.createdAt());
                    }
                    current = requestReview(origin, target);
                }
                // An agent response cannot be recreated with reviewer provenance. Keep it as
                // explicit local history unless it independently reappeared during replay.
                var finalPlan = recovery.plan(target, exactFingerprint, reviewNotes(current));
                remembered = new RecoveredSession(
                        finalPlan.agentHistory(),
                        recoveryNotice(plan),
                        Map.copyOf(reviewerCreationTimes),
                        null
                );
                recoveredSessions.put(key, remembered);
            }

            var context = RecoveryContextKey.from(target);
            if (remembered.notice() == null) {
                recoveryNotices.remove(context);
            } else {
                recoveryNotices.put(
                        context,
                        remembered.notice()
                );
            }

            var presented = presentRecoveredReviewerTimes(
                    reviewNotes(current),
                    remembered.reviewerCreationTimes()
            );
            var merged = HunkReviewRecovery.mergeWithAgentHistory(
                    presented,
                    remembered.agentHistory()
            );
            if (!Objects.equals(remembered.checkpointedNotes(), merged)) {
                recovery.checkpoint(target, exactFingerprint, merged);
                remembered = new RecoveredSession(
                        remembered.agentHistory(),
                        remembered.notice(),
                        remembered.reviewerCreationTimes(),
                        merged
                );
                recoveredSessions.put(key, remembered);
            }
            verifiedFingerprints.put(
                    sessionId,
                    new VerifiedFingerprint(shape, exactFingerprint, reviewNotes(current))
            );
            return withProjectedNotes(current, merged);
        }
    }

    static List<HunkReviewNote> presentRecoveredReviewerTimes(
            List<HunkReviewNote> liveNotes,
            Map<String, String> creationTimes
    ) {
        if (creationTimes.isEmpty()) {
            return liveNotes;
        }
        return liveNotes.stream()
                .map(reviewNote -> {
                    var createdAt = creationTimes.get(reviewNote.note().id());
                    return createdAt == null
                            ? reviewNote
                            : new HunkReviewNote(
                                    reviewNote.filePath(),
                                    reviewNote.note().withDisplayedCreationTime(createdAt)
                            );
                })
                .toList();
    }

    static String recoveryNotice(HunkReviewRecovery.RecoveryPlan plan) {
        if (plan.staleNoteCount() > 0) {
            return plan.staleNoteCount() + (plan.staleNoteCount() == 1
                    ? " locally saved review item was"
                    : " locally saved review items were")
                    + " not restored because the comparison changed.";
        }
        var reviewers = plan.reviewerComments().size();
        var agents = plan.agentHistory().size();
        if (reviewers == 0 && agents == 0) {
            return null;
        }
        var restored = reviewers == 0
                ? ""
                : "Restored " + reviewers + (reviewers == 1 ? " reviewer comment" : " reviewer comments");
        var history = agents == 0
                ? ""
                : (restored.isEmpty() ? "Showing " : "; showing ") + agents
                        + (agents == 1 ? " agent response" : " agent responses")
                        + " from local history";
        return restored + history + ".";
    }

    private static JsonObject withProjectedNotes(
            JsonObject review,
            List<HunkReviewNote> notes
    ) {
        if (notes.equals(reviewNotes(review))) {
            return review;
        }
        var values = new JsonArray();
        notes.forEach(note -> values.add(noteJson(note)));
        review.add("reviewNotes", values);
        return review;
    }

    private static JsonObject noteJson(HunkReviewNote reviewNote) {
        var note = reviewNote.note();
        var value = new JsonObject();
        value.addProperty("noteId", note.id());
        value.addProperty("source", note.source() == HunkNoteSource.USER ? "user" : "agent");
        value.addProperty("filePath", reviewNote.filePath());
        value.addProperty("body", note.body());
        if (note.author() != null) {
            value.addProperty("author", note.author());
        }
        if (note.createdAt() != null) {
            value.addProperty("createdAt", note.createdAt());
        }
        value.addProperty("side", note.side().wireName());
        value.addProperty("line", note.line());
        value.addProperty("endLine", note.endLine());
        value.addProperty("editable", note.editable());
        value.addProperty("recoveredLocally", note.recovered());
        return value;
    }

    private JsonObject requestReview(URI origin, ReviewTarget target) throws HunkSessionException {

        var request = new JsonObject();
        request.addProperty("action", READ_REVIEW);
        request.add("selector", selector(target));
        request.addProperty("includeNotes", true);

        final JsonObject response;
        try {
            response = responseObject(post(origin.resolve("/session-api"), request), "review");
        } catch (HunkSessionException error) {
            // The authenticated v0.23 broker deliberately hides selector-resolution errors.
            // Recover actionable absence/ambiguity from its authorized list, not from guesses
            // about a generic protocol-validation-failed response.
            if (error.getMessage().equals("protocol-validation-failed")
                    && request.getAsJsonObject("selector").has("repoRoot")) {
                var list = new JsonObject();
                list.addProperty("action", "list");
                var sessions = responseObject(post(origin.resolve("/session-api"), list), "list")
                        .getAsJsonArray("sessions");
                var matching = sessions == null ? 0 : sessions.asList().stream()
                        .filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
                        .filter(session -> target.repositoryRoot().toString().equals(string(session, "repoRoot")))
                        .count();
                if (matching == 0) throw new HunkSessionException("No active session matches repoRoot " + target.repositoryRoot());
                if (matching > 1) throw new HunkSessionException("Multiple active sessions match repoRoot " + target.repositoryRoot());
            }
            throw error;
        }
        var review = response.getAsJsonObject("review");
        if (review == null) {
            throw new HunkSessionException("Hunk's review response did not contain a review.");
        }
        var notes = reviewNotes(review);
        var reviewerNotes = notes.stream()
                .filter(note -> note.note().source() == HunkNoteSource.USER)
                .count();
        var agentNotes = notes.stream()
                .filter(note -> note.note().source() == HunkNoteSource.AGENT)
                .count();
        HunkDiagnostics.info(LOG, "review-snapshot", () -> "target=" + target.displayName()
                + " selector=" + selectorKind(request)
                + " sessionId=" + diagnosticProperty(review, "sessionId")
                + " inputKind=" + diagnosticProperty(review, "inputKind")
                + " files=" + arraySize(review, "files")
                + " liveComments=" + diagnosticProperty(review, "liveCommentCount")
                + " reviewNotes=" + notes.size()
                + " reviewerNotes=" + reviewerNotes
                + " agentNotes=" + agentNotes);
        return review;
    }

    private static void validateLineTarget(JsonObject review, ReviewTarget target)
            throws HunkSessionException {
        var files = review.getAsJsonArray("files");
        if (files == null) {
            throw new HunkSessionException(
                    "Hunk's review response did not contain its diff files."
            );
        }

        var file = files.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(candidate -> target.filePath().equals(string(candidate, "path"))
                        || target.filePath().equals(string(candidate, "previousPath")))
                .findFirst()
                .orElseThrow(() -> new HunkSessionException(
                        "The active Hunk review does not contain " + target.filePath() + "."
                ));
        hunkIndex(file, target);
    }

    static int hunkIndex(JsonObject file, ReviewTarget target) throws HunkSessionException {
        var hunks = file.getAsJsonArray("hunks");
        if (hunks != null) {
            var rangeName = target.side() == ReviewSide.NEW ? "newRange" : "oldRange";
            var matching = hunks.asList().stream()
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .filter(hunk -> rangeContains(hunk.getAsJsonArray(rangeName), target.line())
                            && rangeContains(hunk.getAsJsonArray(rangeName), target.endLine()))
                    .map(hunk -> nonNegativeInt(hunk.get("index")))
                    .flatMap(Optional::stream)
                    .findFirst();
            if (matching.isPresent()) {
                return matching.get();
            }
        }
        throw new HunkSessionException(
                target.displayName() + " is not part of a hunk in the active Hunk review."
        );
    }

    private static boolean rangeContains(JsonArray range, int line) {
        if (range == null || range.size() != 2) {
            return false;
        }
        var start = positiveInt(range.get(0));
        var end = positiveInt(range.get(1));
        return start != null && end != null && line >= start && line <= end;
    }

    private static void requireRemoved(JsonObject response) throws HunkSessionException {
        var result = response.getAsJsonObject("result");
        if (result == null || !result.has("removed")) {
            throw new HunkSessionException("Hunk returned an invalid comment-rm result.");
        }
        if (!result.get("removed").getAsBoolean()) {
            throw new HunkSessionException("Hunk did not remove this reviewer comment.");
        }
    }

    private void requireCapability(URI origin, String requiredAction) throws HunkSessionException {
        var capabilities = responseObject(
                get(origin.resolve("/session-api/capabilities")),
                "capabilities"
        );
        var actions = capabilities.getAsJsonArray("actions");
        var supported = actions != null && actions.asList().stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .anyMatch(requiredAction::equals);
        if (!supported) {
            throw new HunkSessionException(
                    "This Hunk build does not expose the required `" + requiredAction
                            + "` session action. Update upstream Hunk when IDE review actions become available."
            );
        }
    }

    private JsonObject selector(ReviewTarget target) {
        var sessionId = trimmed(HunkSettings.getInstance().getState().sessionId);
        if (sessionId.isEmpty()) {
            sessionId = launcher.sessionId(target);
        }
        var selector = new JsonObject();
        sessionId.ifPresentOrElse(
                id -> selector.addProperty("sessionId", id),
                () -> selector.addProperty("repoRoot", target.repositoryRoot().toString())
        );
        return selector;
    }

    private static HunkSessionException unmanagedReview() {
        return new HunkSessionException(
                "This live Hunk review is readable, but GoLand does not own its reviewer composer. "
                        + "Start an IDE-managed review here; the external Hunk TUI can remain open."
        );
    }

    static Optional<HunkSessionNote> parseNote(JsonObject value) {
        try {
            var id = primitiveString(firstProperty(value, "noteId", "commentId"));
            var body = primitiveString(firstProperty(value, "body", "summary"));
            var location = noteLocation(value);
            if (id == null || body == null || location == null) {
                return Optional.empty();
            }

            var source = HunkNoteSource.fromWireName(string(value, "source"));
            return Optional.of(new HunkSessionNote(
                    id,
                    source,
                    body,
                    string(value, "author"),
                    string(value, "createdAt"),
                    location.side(),
                    location.line(),
                    location.endLine(),
                    booleanValue(value.get("editable")).orElse(source == HunkNoteSource.USER),
                    booleanValue(value.get("recoveredLocally")).orElse(false)
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static NoteLocation noteLocation(JsonObject value) {
        var explicitSide = ReviewSide.fromWireName(string(value, "side"));
        var explicitLine = positiveInt(value.get("line"));
        if (explicitSide != null && explicitLine != null) {
            var endLine = value.has("endLine") ? positiveInt(value.get("endLine")) : explicitLine;
            return endLine == null ? null : new NoteLocation(explicitSide, explicitLine, endLine);
        }

        var newLine = rangeStart(value.getAsJsonArray("newRange"));
        if (newLine != null) {
            return noteRange(ReviewSide.NEW, value.getAsJsonArray("newRange"));
        }
        var oldLine = rangeStart(value.getAsJsonArray("oldRange"));
        return oldLine == null ? null : noteRange(ReviewSide.OLD, value.getAsJsonArray("oldRange"));
    }

    private static NoteLocation noteRange(ReviewSide side, JsonArray range) {
        if (range == null || range.size() != 2) {
            return null;
        }
        var start = positiveInt(range.get(0));
        var end = positiveInt(range.get(1));
        return start == null || end == null ? null : new NoteLocation(side, start, end);
    }

    private static Integer rangeStart(JsonArray range) {
        return range == null || range.isEmpty() ? null : positiveInt(range.get(0));
    }

    private static JsonElement firstProperty(JsonObject value, String... names) {
        return Arrays.stream(names)
                .filter(value::has)
                .map(value::get)
                .findFirst()
                .orElse(null);
    }

    private static String string(JsonObject value, String name) {
        return primitiveString(value.get(name));
    }

    private static String primitiveString(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static Integer positiveInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        var value = element.getAsInt();
        return value > 0 ? value : null;
    }

    private static Optional<Integer> nonNegativeInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        var value = element.getAsInt();
        return value >= 0 ? Optional.of(value) : Optional.empty();
    }

    private static Optional<Boolean> booleanValue(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? Optional.of(element.getAsBoolean())
                : Optional.empty();
    }

    private HunkSessionTransport.Response get(URI uri) throws HunkSessionException {
        return send(uri, null, "capabilities", "none");
    }

    private HunkSessionTransport.Response post(URI uri, JsonObject payload) throws HunkSessionException {
        return send(uri, payload, diagnosticProperty(payload, "action"), selectorKind(payload));
    }

    private HunkSessionTransport.Response send(
            URI uri,
            JsonObject payload,
            String action,
            String selector
    ) throws HunkSessionException {
        var requestId = REQUEST_SEQUENCE.incrementAndGet();
        var startedAt = System.nanoTime();
        HunkDiagnostics.info(LOG, "http-start", () -> "requestId=" + requestId
                + " method=" + (payload == null ? "GET" : "POST")
                + " path=" + uri.getPath()
                + " action=" + action
                + " selector=" + selector);
        final HunkSessionTransport.Response response;
        try {
            response = payload == null ? transport.get(uri, TIMEOUT) : transport.post(uri, payload, TIMEOUT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logHttpFailure(requestId, uri, action, selector, startedAt, error);
            throw new HunkSessionException("Interrupted while contacting the Hunk session daemon.", error);
        } catch (HunkAuthenticationException error) {
            logHttpFailure(requestId, uri, action, selector, startedAt, error);
            // An unsafe identity or protocol mismatch must not trigger an automatic TUI launch.
            throw new HunkSessionException(error.getMessage(), error);
        } catch (IOException error) {
            logHttpFailure(requestId, uri, action, selector, startedAt, error);
            throw new HunkSessionException(
                    "Could not reach the Hunk session daemon at " + uri.getScheme() + "://" + uri.getAuthority()
                            + ". " + error.getMessage(),
                    error
            );
        }

        HunkDiagnostics.info(LOG, "http-complete", () -> "requestId=" + requestId
                + " method=" + (payload == null ? "GET" : "POST")
                + " path=" + uri.getPath()
                + " action=" + action
                + " selector=" + selector
                + " status=" + response.statusCode()
                + " responseBytes=" + response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HunkSessionException(readError(response.body(), response.statusCode()));
        }
        return response;
    }

    private void logHttpFailure(
            long requestId,
            URI uri,
            String action,
            String selector,
            long startedAt,
            Exception error
    ) {
        HunkDiagnostics.warning(LOG, "http-failed", () -> "requestId=" + requestId
                + " path=" + uri.getPath()
                + " action=" + action
                + " selector=" + selector
                + " errorType=" + error.getClass().getSimpleName()
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static int arraySize(JsonObject object, String name) {
        var value = object.getAsJsonArray(name);
        return value == null ? 0 : value.size();
    }

    private static String diagnosticProperty(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return "none";
        }
        var text = value.getAsString();
        return text.isBlank() ? "none" : text;
    }

    private static String selectorKind(JsonObject payload) {
        var selector = payload.getAsJsonObject("selector");
        if (selector == null) {
            return "none";
        }
        if (selector.has("sessionId")) {
            return "session-id";
        }
        if (selector.has("repoRoot")) {
            return "repo-root";
        }
        return "unknown";
    }

    private static JsonObject responseObject(HunkSessionTransport.Response response, String kind)
            throws HunkSessionException {
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new HunkSessionException("Hunk returned an invalid " + kind + " response.", error);
        }
    }

    private static Optional<String> trimmed(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(text -> !text.isEmpty());
    }

    private static URI origin(String host, int port) {
        var urlHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return URI.create("http://" + urlHost + ":" + port);
    }

    private static String readError(String body, int status) {
        try {
            var error = JsonParser.parseString(body).getAsJsonObject().get("error");
            if (error != null && error.isJsonPrimitive()) {
                return error.getAsString();
            }
        } catch (RuntimeException ignored) {
            // Fall through to a bounded generic error.
        }
        return "Hunk session request failed with HTTP " + status + ".";
    }

    private record NoteLocation(ReviewSide side, int line, int endLine) {
    }

    private record RecoveredSessionKey(String sessionId, String fingerprint) {
    }

    private record RecoveredSession(
            List<HunkReviewNote> agentHistory,
            String notice,
            Map<String, String> reviewerCreationTimes,
            List<HunkReviewNote> checkpointedNotes
    ) {
    }

    private record RecoveryContextKey(String repositoryRoot, HunkReviewInput input) {
        static RecoveryContextKey from(ReviewTarget target) {
            return new RecoveryContextKey(
                    target.repositoryRoot().toString(),
                    target.reviewInput()
            );
        }
    }

    private record VerifiedFingerprint(
            String shape,
            String fingerprint,
            List<HunkReviewNote> nativeNotes
    ) {
    }

}
