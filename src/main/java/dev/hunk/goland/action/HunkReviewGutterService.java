package dev.hunk.goland.action;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.Alarm;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.HunkReviewInput;
import dev.hunk.goland.model.HunkReviewNote;
import dev.hunk.goland.model.ReviewTarget;
import dev.hunk.goland.service.HunkSessionException;
import dev.hunk.goland.service.HunkSessionService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Keeps open editors decorated with live review-comment and agent-response markers. */
@Service(Service.Level.PROJECT)
public final class HunkReviewGutterService implements Disposable {
    private static final int REFRESH_MILLIS = 2_000;

    private final Project project;
    private final HunkSessionService session;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final AtomicBoolean started = new AtomicBoolean();
    private final Map<Editor, EditorMarkers> markers = new IdentityHashMap<>();
    private final Map<TargetKey, List<HunkSessionNote>> noteCache = new LinkedHashMap<>();
    private final Map<ReviewKey, HunkReviewIndex> reviewIndexes = new LinkedHashMap<>();
    private final List<NoteSubscription> subscriptions = new ArrayList<>();
    private final List<IndexSubscription> indexSubscriptions = new ArrayList<>();
    private HunkReviewIndex currentIndex = HunkReviewIndex.empty();
    private boolean loading;
    private boolean refreshRequested;
    private volatile boolean stopping;

    public HunkReviewGutterService(Project project) {
        this.project = project;
        this.session = project.getService(HunkSessionService.class);
    }

    void start() {
        if (started.compareAndSet(false, true)) {
            project.getMessageBus().connect(this).subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
                @Override
                public void projectClosing(Project closingProject) {
                    if (closingProject == project) {
                        stopPolling();
                    }
                }
            });
            schedule(0);
        }
    }

    void refreshSoon() {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::refreshSoon);
            return;
        }
        if (started.get() && !stopping && !project.isDisposed()) {
            if (loading) {
                refreshRequested = true;
                return;
            }
            alarm.cancelAllRequests();
            schedule(0);
        }
    }

    void watchLine(
            ReviewTarget target,
            Disposable parent,
            Consumer<List<HunkSessionNote>> onNotes,
            Consumer<HunkSessionException> onError
    ) {
        var subscription = new NoteSubscription(
                TargetKey.from(target),
                target.line(),
                onNotes,
                onError
        );
        subscriptions.add(subscription);
        Disposer.register(parent, () -> subscriptions.remove(subscription));
        var cached = noteCache.get(subscription.target());
        if (cached != null) {
            onNotes.accept(notesOnLine(cached, subscription.line()));
        }
        refreshSoon();
    }

    void watchIndex(Disposable parent, Consumer<HunkReviewIndex> onIndex) {
        var subscription = new IndexSubscription(onIndex);
        indexSubscriptions.add(subscription);
        Disposer.register(parent, () -> indexSubscriptions.remove(subscription));
        onIndex.accept(currentIndex);
        refreshSoon();
    }

    HunkReviewIndex currentIndex(ReviewTarget context) {
        return reviewIndexes.getOrDefault(ReviewKey.from(context), HunkReviewIndex.empty());
    }

    HunkReviewIndex currentIndex() {
        return currentIndex;
    }

    private void schedule(int delayMillis) {
        if (!stopping) {
            alarm.addRequest(this::refresh, delayMillis);
        }
    }

    /** Runs on the UI thread only long enough to capture stable editor identities. */
    private void refresh() {
        if (stopping || project.isDisposed()) {
            return;
        }
        if (loading) {
            refreshRequested = true;
            return;
        }
        var targets = Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .filter(editor -> editor.getProject() == project)
                .filter(editor -> !editor.isDisposed() && editor.getContentComponent().isShowing())
                .map(editor -> ReviewTargetResolver.resolve(project, editor))
                .flatMap(resolution -> switch (resolution) {
                    case TargetResolution.Resolved(var target, var editor, var ignoredEditorLine) ->
                            java.util.stream.Stream.of(new EditorTarget(editor, target));
                    case TargetResolution.Unavailable ignored -> java.util.stream.Stream.empty();
                })
                .toList();

        if (targets.isEmpty()) {
            retainOnly(List.of());
            schedule(REFRESH_MILLIS);
            return;
        }

        loading = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> load(targets));
    }

    private void load(List<EditorTarget> targets) {
        var notesByReview = new LinkedHashMap<ReviewKey, LoadResult>();
        targets.forEach(editorTarget -> notesByReview.computeIfAbsent(
                ReviewKey.from(editorTarget.target()),
                ignored -> load(editorTarget.target())
        ));
        ApplicationManager.getApplication().invokeLater(() -> {
            if (stopping || project.isDisposed()) {
                return;
            }
            loading = false;
            retainOnly(targets.stream().map(EditorTarget::editor).toList());
            notesByReview.forEach((review, result) -> {
                switch (result) {
                    case LoadResult.Loaded(var notes) -> {
                        targets.stream()
                                .filter(target -> ReviewKey.from(target.target()).equals(review))
                                .forEach(target -> {
                                    var fileNotes = fileNotes(notes, target.target());
                                    var targetKey = TargetKey.from(target.target());
                                    noteCache.put(targetKey, fileNotes);
                                    notifyNotes(targetKey, fileNotes);
                                    apply(target, fileNotes);
                                });
                        targets.stream()
                                .filter(target -> ReviewKey.from(target.target()).equals(review))
                                .findFirst()
                                .ifPresent(target -> publishIndex(HunkReviewIndex.from(target.target(), notes)));
                    }
                    case LoadResult.Unavailable(var error) -> targets.stream()
                            .filter(target -> ReviewKey.from(target.target()).equals(review))
                            .map(target -> TargetKey.from(target.target()))
                            .distinct()
                            .forEach(target -> notifyError(target, error));
                }
            });
            if (refreshRequested) {
                refreshRequested = false;
                schedule(0);
            } else {
                schedule(REFRESH_MILLIS);
            }
        });
    }

    private LoadResult load(ReviewTarget target) {
        try {
            return new LoadResult.Loaded(session.listReviewNotes(target));
        } catch (HunkSessionException error) {
            // A daemon can legitimately be absent while the IDE remains open. Preserve known markers.
            return new LoadResult.Unavailable(error);
        }
    }

    private static List<HunkSessionNote> fileNotes(
            List<HunkReviewNote> notes,
            ReviewTarget target
    ) {
        return notes.stream()
                .filter(note -> note.filePath().equals(target.filePath()))
                .map(HunkReviewNote::note)
                .filter(note -> note.side() == target.side())
                .toList();
    }

    private void publishIndex(HunkReviewIndex index) {
        var reviewKey = ReviewKey.from(index);
        var previous = reviewIndexes.put(reviewKey, index);
        if (index.equals(previous) && index.equals(currentIndex)) {
            return;
        }
        currentIndex = index;
        indexSubscriptions.forEach(subscription -> subscription.onIndex().accept(index));
        var frame = WindowManager.getInstance().getFrame(project);
        if (frame != null) {
            // FilePathIconProvider is consulted while native VCS trees render. A repaint makes a
            // newly added or removed Hunk badge visible without rebuilding GoLand's tree model.
            frame.repaint();
        }
    }

    private void notifyNotes(TargetKey target, List<HunkSessionNote> notes) {
        subscriptions.stream()
                .filter(subscription -> subscription.target().equals(target))
                .forEach(subscription -> subscription.onNotes().accept(
                        notesOnLine(notes, subscription.line())
                ));
    }

    private void notifyError(TargetKey target, HunkSessionException error) {
        subscriptions.stream()
                .filter(subscription -> subscription.target().equals(target))
                .forEach(subscription -> subscription.onError().accept(error));
    }

    private static List<HunkSessionNote> notesOnLine(List<HunkSessionNote> notes, int line) {
        return notes.stream().filter(note -> note.line() == line).toList();
    }

    private void apply(EditorTarget editorTarget, List<HunkSessionNote> notes) {
        var editor = editorTarget.editor();
        if (editor.isDisposed()) {
            return;
        }
        var summaries = notes.stream()
                .filter(note -> ReviewTargetResolver.editorLine(
                        editor,
                        editorTarget.target().side(),
                        note.line()
                ).isPresent())
                .collect(Collectors.groupingBy(
                        HunkSessionNote::line,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), HunkGutterSummary::from)
                ));
        var targetKey = TargetKey.from(editorTarget.target());
        var current = markers.get(editor);
        if (current != null && current.target().equals(targetKey) && current.summaries().equals(summaries)) {
            return;
        }
        dispose(current);

        var highlighters = summaries.entrySet().stream()
                .map(entry -> addMarker(editor, editorTarget.target(), entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream)
                .toList();
        markers.put(editor, new EditorMarkers(targetKey, summaries, highlighters));
    }

    private Optional<RangeHighlighter> addMarker(
            Editor editor,
            ReviewTarget fileTarget,
            int line,
            HunkGutterSummary summary
    ) {
        var editorLine = ReviewTargetResolver.editorLine(editor, fileTarget.side(), line);
        if (editorLine.isEmpty()) {
            return Optional.empty();
        }
        var highlighter = editor.getMarkupModel().addLineHighlighter(
                editorLine.getAsInt() - 1,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null
        );
        var target = new ReviewTarget(
                fileTarget.repositoryRoot(),
                fileTarget.filePath(),
                fileTarget.side(),
                line,
                fileTarget.reviewInput()
        );
        highlighter.setGutterIconRenderer(new HunkGutterIconRenderer(
                project,
                editor,
                target,
                editorLine.getAsInt(),
                summary
        ));
        return Optional.of(highlighter);
    }

    private void retainOnly(List<Editor> activeEditors) {
        markers.entrySet().removeIf(entry -> {
            var stale = entry.getKey().isDisposed() || !activeEditors.contains(entry.getKey());
            if (stale) {
                dispose(entry.getValue());
            }
            return stale;
        });
    }

    private static void dispose(EditorMarkers value) {
        if (value != null) {
            value.highlighters().forEach(RangeHighlighter::dispose);
        }
    }

    @Override
    public void dispose() {
        stopPolling();
        markers.values().forEach(HunkReviewGutterService::dispose);
        markers.clear();
        noteCache.clear();
        reviewIndexes.clear();
        subscriptions.clear();
        indexSubscriptions.clear();
    }

    private void stopPolling() {
        stopping = true;
        alarm.cancelAllRequests();
    }

    private record EditorTarget(Editor editor, ReviewTarget target) {
    }

    private record TargetKey(
            String repositoryRoot,
            String filePath,
            String side,
            HunkReviewInput input
    ) {
        static TargetKey from(ReviewTarget target) {
            return new TargetKey(
                    target.repositoryRoot().toString(),
                    target.filePath(),
                    target.side().wireName(),
                    target.reviewInput()
            );
        }
    }

    private record ReviewKey(String repositoryRoot, HunkReviewInput input) {
        static ReviewKey from(ReviewTarget target) {
            return new ReviewKey(target.repositoryRoot().toString(), target.reviewInput());
        }

        static ReviewKey from(HunkReviewIndex index) {
            return new ReviewKey(index.repositoryRoot().toString(), index.reviewInput());
        }
    }

    private record EditorMarkers(
            TargetKey target,
            Map<Integer, HunkGutterSummary> summaries,
            List<RangeHighlighter> highlighters
    ) {
    }

    private record NoteSubscription(
            TargetKey target,
            int line,
            Consumer<List<HunkSessionNote>> onNotes,
            Consumer<HunkSessionException> onError
    ) {
    }

    private record IndexSubscription(Consumer<HunkReviewIndex> onIndex) {
    }

    private sealed interface LoadResult {
        record Loaded(List<HunkReviewNote> notes) implements LoadResult {
        }

        record Unavailable(HunkSessionException error) implements LoadResult {
        }
    }
}
