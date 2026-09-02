package dev.hunk.goland.action;

import com.intellij.diff.chains.DiffRequestChain;
import com.intellij.diff.chains.DiffRequestProducer;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.FileContent;
import com.intellij.diff.impl.CacheDiffRequestChainProcessor;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.ide.DataManager;
import com.intellij.openapi.ListSelection;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorGutterComponentEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import dev.hunk.goland.model.HunkReviewInput;
import dev.hunk.goland.model.PathMapping;
import dev.hunk.goland.model.ReviewSide;
import dev.hunk.goland.model.ReviewTarget;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class ReviewTargetResolver {
    private static final Optional<Key<?>> VCS_REVISION_INFO = vcsRevisionInfoKey();
    private static final String UNIFIED_DIFF_VIEWER =
            "com.intellij.diff.tools.fragmented.UnifiedDiffViewer";
    private static final String TEXT_DIFF_REQUIRED =
            "Choose the current/right side of a text diff; merge and three-sided views are not supported yet.";

    private ReviewTargetResolver() {
    }

    public static TargetResolution resolve(AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return unavailable("Open a project before adding a Hunk review note.");
        }

        var editor = focusedEditor(event);
        if (editor == null) {
            return unavailable("Place the caret in a code editor or text diff first.");
        }

        var currentContent = event.getData(DiffDataKeys.CURRENT_CONTENT);
        return switch (resolveSide(event, currentContent, editor)) {
            case SideResolution.Resolved(var side) -> isUnsupportedDiffSide(editor, side)
                    ? unavailable("Add Hunk comments from the current/right side of a diff.")
                    : resolveTarget(event, project, editor, currentContent, side);
            case SideResolution.Unavailable(var reason) -> unavailable(reason);
        };
    }

    /** Resolve an already-open editor for asynchronous gutter-note discovery. */
    static TargetResolution resolve(Project project, Editor editor) {
        var side = isDiffEditor(editor)
                ? resolveDiffSide(project, editor)
                : new SideResolution.Resolved(ReviewSide.NEW);
        return switch (side) {
            case SideResolution.Resolved(var resolvedSide) -> isUnsupportedDiffSide(editor, resolvedSide)
                    ? unavailable("Hunk diff comments are shown on the right side only.")
                    : resolveOpenEditor(project, editor, resolvedSide);
            case SideResolution.Unavailable(var reason) -> unavailable(reason);
        };
    }

    /** Prefer the popup's component-local diff editor, then the diff viewer's current editor. */
    private static Editor focusedEditor(AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        if (editor != null && isDiffEditor(editor)) {
            return editor;
        }
        var diffEditor = event.getData(DiffDataKeys.CURRENT_EDITOR);
        if (diffEditor != null && isDiffEditor(diffEditor)) {
            return diffEditor;
        }
        return editor != null ? editor : diffEditor;
    }

    private static TargetResolution resolveTarget(
            AnActionEvent event,
            Project project,
            Editor editor,
            DiffContent currentContent,
            ReviewSide side
    ) {
        var editorContext = DataManager.getInstance().getDataContext(editor.getContentComponent());
        var request = firstPresent(
                event.getData(DiffDataKeys.DIFF_REQUEST),
                DiffDataKeys.DIFF_REQUEST.getData(editorContext)
        ).orElse(null);
        var change = firstPresent(
                event.getData(VcsDataKeys.CURRENT_CHANGE),
                VcsDataKeys.CURRENT_CHANGE.getData(editorContext)
        ).orElse(null);
        var reviewInput = reviewInput(
                change,
                firstPresent(
                        event.getData(VcsDataKeys.CHANGES_SELECTION),
                        VcsDataKeys.CHANGES_SELECTION.getData(editorContext)
                ).orElse(null),
                requestInput(request).or(() -> comparisonInput(editor))
        );
        var filePath = filePathForSide(change, side);
        var virtualFile = firstPresent(
                contentFile(contentForSide(request, currentContent, side)),
                editor.getVirtualFile(),
                event.getData(CommonDataKeys.VIRTUAL_FILE)
        ).orElse(null);
        if (filePath == null && virtualFile == null) {
            return unavailable("The focused editor is not backed by a local file.");
        }

        var rootPath = repositoryRoot(project, filePath, virtualFile).orElse(null);
        if (rootPath == null) {
            return unavailable("Could not determine the repository root for this file.");
        }

        var absoluteFile = Path.of(filePath != null ? filePath.getPath() : virtualFile.getPath());
        var editorLine = focusedLine(event.getDataContext(), editor);
        var reviewLine = reviewLine(event.getDataContext(), editor, side, editorLine);
        if (reviewLine.isEmpty()) {
            return unavailable("Choose a line that exists on the current/right side of this diff.");
        }
        try {
            var target = new ReviewTarget(
                    rootPath,
                    PathMapping.repositoryRelative(rootPath, absoluteFile),
                    side,
                    reviewLine.getAsInt(),
                    reviewInput
            );
            return new TargetResolution.Resolved(target, editor, editorLine);
        } catch (IllegalArgumentException error) {
            return unavailable("The focused file is outside the detected repository root.");
        }
    }

    private static TargetResolution resolveOpenEditor(
            Project project,
            Editor editor,
            ReviewSide side
    ) {
        var context = DataManager.getInstance().getDataContext(editor.getContentComponent());
        var request = DiffDataKeys.DIFF_REQUEST.getData(context);
        var change = VcsDataKeys.CURRENT_CHANGE.getData(context);
        var reviewInput = reviewInput(
                change,
                VcsDataKeys.CHANGES_SELECTION.getData(context),
                requestInput(request).or(() -> comparisonInput(editor))
        );
        var filePath = filePathForSide(change, side);
        var content = request instanceof ContentDiffRequest contentRequest
                && contentRequest.getContents().size() == 2
                ? contentRequest.getContents().get(side == ReviewSide.OLD ? 0 : 1)
                : null;
        var virtualFile = firstPresent(
                contentFile(content),
                editor.getVirtualFile(),
                FileDocumentManager.getInstance().getFile(editor.getDocument()),
                peerDiffFile(project, editor)
        ).orElse(null);
        if (filePath == null && virtualFile == null) {
            return unavailable("The open editor is not backed by a local file.");
        }

        var rootPath = repositoryRoot(project, filePath, virtualFile).orElse(null);
        if (rootPath == null) {
            return unavailable("Could not determine the repository root for this file.");
        }

        var editorLine = focusedLine(editor);
        var reviewLine = reviewLine(context, editor, side, editorLine);
        if (reviewLine.isEmpty()) {
            return unavailable("Choose a line that exists on the current/right side of this diff.");
        }
        try {
            var absoluteFile = Path.of(filePath != null ? filePath.getPath() : virtualFile.getPath());
            return new TargetResolution.Resolved(new ReviewTarget(
                    rootPath,
                    PathMapping.repositoryRelative(rootPath, absoluteFile),
                    side,
                    reviewLine.getAsInt(),
                    reviewInput
            ), editor, editorLine);
        } catch (IllegalArgumentException error) {
            return unavailable("The open file is outside the detected repository root.");
        }
    }

    private static Optional<Path> repositoryRoot(
            Project project,
            FilePath filePath,
            VirtualFile virtualFile
    ) {
        var vcs = ProjectLevelVcsManager.getInstance(project);
        var root = filePath != null ? vcs.getVcsRootFor(filePath) : vcs.getVcsRootFor(virtualFile);
        if (root != null) {
            return Optional.of(Path.of(root.getPath()));
        }
        return Optional.ofNullable(project.getBasePath()).map(Path::of);
    }

    private static SideResolution resolveSide(
            AnActionEvent event,
            DiffContent currentContent,
            Editor editor
    ) {
        if (isUnifiedDiffEditor(event.getData(DiffDataKeys.DIFF_VIEWER), editor)) {
            return new SideResolution.Resolved(ReviewSide.NEW);
        }
        var request = event.getData(DiffDataKeys.DIFF_REQUEST);
        if (currentContent == null && request == null) {
            return isDiffEditor(editor)
                    ? resolveDiffSide(event.getProject(), editor)
                    : new SideResolution.Resolved(ReviewSide.NEW);
        }
        if (request instanceof ContentDiffRequest contentRequest) {
            return resolveContentSide(contentRequest, currentContent, editor);
        }
        return new SideResolution.Unavailable(
                "Hunk comments currently support the normal editor and text diffs."
        );
    }

    private static SideResolution resolveContentSide(
            ContentDiffRequest request,
            DiffContent currentContent,
            Editor editor
    ) {
        List<DiffContent> contents = request.getContents();
        if (contents.size() != 2) {
            return new SideResolution.Unavailable(TEXT_DIFF_REQUIRED);
        }

        var editorIndex = IntStream.range(0, contents.size())
                .filter(index -> contents.get(index) instanceof DocumentContent content
                        && content.getDocument() == editor.getDocument())
                .findFirst()
                .orElse(currentContent == null ? -1 : contents.indexOf(currentContent));
        return switch (editorIndex) {
            case 0 -> new SideResolution.Resolved(ReviewSide.OLD);
            case 1 -> new SideResolution.Resolved(ReviewSide.NEW);
            default -> new SideResolution.Unavailable("Could not identify the focused side of this diff.");
        };
    }

    /** Resolve a diff editor through its own data context, including one-visible-pane diffs. */
    private static SideResolution resolveDiffSide(Project project, Editor editor) {
        var context = DataManager.getInstance().getDataContext(editor.getContentComponent());
        if (isUnifiedDiffEditor(DiffDataKeys.DIFF_VIEWER.getData(context), editor)) {
            return new SideResolution.Resolved(ReviewSide.NEW);
        }
        var request = DiffDataKeys.DIFF_REQUEST.getData(context);
        if (request instanceof ContentDiffRequest contentRequest) {
            var side = resolveContentSide(
                    contentRequest,
                    DiffDataKeys.CURRENT_CONTENT.getData(context),
                    editor
            );
            if (side instanceof SideResolution.Resolved) {
                return side;
            }
        }
        return resolveVisibleDiffSide(project, editor);
    }

    private static DiffContent contentForSide(
            DiffRequest request,
            DiffContent currentContent,
            ReviewSide side
    ) {
        if (request instanceof ContentDiffRequest contentRequest
                && contentRequest.getContents().size() == 2) {
            return contentRequest.getContents().get(side == ReviewSide.OLD ? 0 : 1);
        }
        return currentContent;
    }

    /** Resolve diff side when a component-rooted action does not expose the viewer data keys. */
    private static SideResolution resolveVisibleDiffSide(Project project, Editor editor) {
        var visibleEditors = visibleDiffEditors(project);
        if (visibleEditors.size() != 2 || !visibleEditors.contains(editor)) {
            return new SideResolution.Unavailable("Could not identify the focused side of this diff.");
        }
        return new SideResolution.Resolved(
                visibleEditors.getFirst() == editor ? ReviewSide.OLD : ReviewSide.NEW
        );
    }

    private static List<Editor> visibleDiffEditors(Project project) {
        return Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .filter(editor -> editor.getProject() == project)
                .filter(ReviewTargetResolver::isDiffEditor)
                .filter(editor -> editor.getContentComponent().isShowing())
                .sorted((left, right) -> Integer.compare(
                        left.getContentComponent().getLocationOnScreen().x,
                        right.getContentComponent().getLocationOnScreen().x
                ))
                .toList();
    }

    private static VirtualFile peerDiffFile(Project project, Editor editor) {
        if (!isDiffEditor(editor)) {
            return null;
        }
        return visibleDiffEditors(project).stream()
                .filter(candidate -> candidate != editor)
                .map(candidate -> firstPresent(
                        candidate.getVirtualFile(),
                        FileDocumentManager.getInstance().getFile(candidate.getDocument())
                ).orElse(null))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    static boolean isDiffEditor(Editor editor) {
        return DiffUtil.isDiffEditor(editor) || Stream.iterate(
                        editor.getContentComponent(),
                        Objects::nonNull,
                        Component::getParent
                )
                .map(Component::getClass)
                .map(Class::getName)
                .anyMatch(name -> name.startsWith("com.intellij.diff."));
    }

    /** Maps a one-based Hunk line back to the visual editor line used by inlays and markers. */
    static OptionalInt editorLine(Editor editor, ReviewSide side, int reviewLine) {
        var context = DataManager.getInstance().getDataContext(editor.getContentComponent());
        var viewer = DiffDataKeys.DIFF_VIEWER.getData(context);
        if (!isUnifiedDiffEditor(viewer, editor)) {
            return reviewLine <= editor.getDocument().getLineCount()
                    ? OptionalInt.of(reviewLine)
                    : OptionalInt.empty();
        }
        var mapped = transferUnifiedLine(viewer, "transferLineToOnesideStrict", side, reviewLine - 1);
        return mapped >= 0 ? OptionalInt.of(mapped + 1) : OptionalInt.empty();
    }

    /** Maps the visual caret in a unified viewer onto Hunk's native old/new line coordinates. */
    private static OptionalInt reviewLine(
            DataContext context,
            Editor editor,
            ReviewSide side,
            int editorLine
    ) {
        var viewer = DiffDataKeys.DIFF_VIEWER.getData(context);
        if (!isUnifiedDiffEditor(viewer, editor)) {
            return OptionalInt.of(editorLine);
        }
        var mapped = transferUnifiedLine(viewer, "transferLineFromOnesideStrict", side, editorLine - 1);
        return mapped >= 0 ? OptionalInt.of(mapped + 1) : OptionalInt.empty();
    }

    private static int transferUnifiedLine(
            Object viewer,
            String method,
            ReviewSide side,
            int line
    ) {
        try {
            return (int) viewer.getClass()
                    .getMethod(method, Side.class, int.class)
                    .invoke(viewer, side == ReviewSide.NEW ? Side.RIGHT : Side.LEFT, line);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return -1;
        }
    }

    /** Avoid a compile-time dependency on JetBrains' internal unified-viewer implementation. */
    private static boolean isUnifiedDiffEditor(Object viewer, Editor editor) {
        if (viewer == null || !hasType(viewer.getClass(), UNIFIED_DIFF_VIEWER)) {
            return false;
        }
        try {
            return viewer.getClass().getMethod("getEditor").invoke(viewer) == editor;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    private static boolean hasType(Class<?> type, String name) {
        return type != null && (name.equals(type.getName()) || hasType(type.getSuperclass(), name));
    }

    private static boolean isUnsupportedDiffSide(Editor editor, ReviewSide side) {
        return side == ReviewSide.OLD && isDiffEditor(editor);
    }

    private static FilePath filePathForSide(Change change, ReviewSide side) {
        if (change == null) {
            return null;
        }
        ContentRevision revision = switch (side) {
            case OLD -> change.getBeforeRevision();
            case NEW -> change.getAfterRevision();
        };
        return revision == null ? null : revision.getFile();
    }

    /**
     * A selected added/deleted file has only one content revision, so search the comparison's
     * complete change selection for a file that carries both endpoints.
     */
    static HunkReviewInput reviewInput(Change current, ListSelection<Change> selection) {
        return reviewInput(current, selection, Optional.empty());
    }

    /** Resolve the comparison from the request-local VCS data used by editor gutter actions. */
    static HunkReviewInput reviewInput(
            Change current,
            ListSelection<Change> selection,
            DiffRequest request
    ) {
        return reviewInput(current, selection, requestInput(request));
    }

    private static HunkReviewInput reviewInput(
            Change current,
            ListSelection<Change> selection,
            Optional<HunkReviewInput> comparisonInput
    ) {
        var changes = selection == null ? Stream.<Change>empty() : selection.getList().stream();
        return Stream.concat(Stream.ofNullable(current), changes)
                .map(ReviewTargetResolver::changeInput)
                .flatMap(Optional::stream)
                .findFirst()
                .or(() -> comparisonInput)
                .orElseGet(HunkReviewInput::workingTree);
    }

    /**
     * The selected file in a branch comparison may be newly added and therefore carry only its
     * after revision. The surrounding diff chain still owns every comparison change, including a
     * modified file with both endpoints, so use it to preserve the comparison's exact range.
     */
    private static Optional<HunkReviewInput> comparisonInput(Editor targetEditor) {
        return Stream.iterate(
                        targetEditor.getContentComponent(),
                        Objects::nonNull,
                        Component::getParent
                )
                .map(ReviewTargetResolver::diffProcessor)
                .flatMap(Optional::stream)
                .map(ReviewTargetResolver::comparisonInput)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Obtain the active processor without linking the plugin to JetBrains' internal panel type. */
    private static Optional<DiffRequestProcessor> diffProcessor(Component panel) {
        try {
            return Optional.ofNullable(panel.getClass().getMethod("getProcessor").invoke(panel))
                    .filter(DiffRequestProcessor.class::isInstance)
                    .map(DiffRequestProcessor.class::cast);
        } catch (ReflectiveOperationException error) {
            return Optional.empty();
        }
    }

    private static Optional<HunkReviewInput> comparisonInput(
            DiffRequestProcessor processor
    ) {
        return requestInput(processor.getActiveRequest())
                .or(() -> processorChanges(processor)
                .map(ReviewTargetResolver::changeInput)
                .flatMap(Optional::stream)
                .findFirst())
                .or(() -> requestChain(processor).flatMap(ReviewTargetResolver::comparisonInput));
    }

    private static Optional<HunkReviewInput> comparisonInput(
            DiffRequestChain chain
    ) {
        return chain.getRequests().stream()
                .map(ReviewTargetResolver::requestChange)
                .flatMap(Optional::stream)
                .map(ReviewTargetResolver::changeInput)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Bridge the VCS implementation processor that is hidden from the compile-time platform API. */
    private static Optional<DiffRequestChain> requestChain(DiffRequestProcessor processor) {
        if (processor instanceof CacheDiffRequestChainProcessor cacheProcessor) {
            return Optional.of(cacheProcessor.getRequestChain());
        }
        try {
            return Optional.ofNullable(processor.getClass().getMethod("getChain").invoke(processor))
                    .filter(DiffRequestChain.class::isInstance)
                    .map(DiffRequestChain.class::cast);
        } catch (ReflectiveOperationException error) {
            return Optional.empty();
        }
    }

    /** Read the comparison rows already held by the VCS changes-browser processor. */
    private static Stream<Change> processorChanges(DiffRequestProcessor processor) {
        try {
            var rows = processor.getClass().getMethod("iterateAllChanges").invoke(processor);
            return switch (rows) {
                case Iterable<?> iterable -> StreamSupport.stream(iterable.spliterator(), false)
                        .map(ReviewTargetResolver::rowChange)
                        .flatMap(Optional::stream);
                default -> Stream.empty();
            };
        } catch (ReflectiveOperationException error) {
            return Stream.empty();
        }
    }

    private static Optional<Change> rowChange(Object row) {
        try {
            return Optional.ofNullable(row.getClass().getMethod("getUserObject").invoke(row))
                    .filter(Change.class::isInstance)
                    .map(Change.class::cast);
        } catch (ReflectiveOperationException error) {
            return Optional.empty();
        }
    }

    /**
     * JetBrains does not expose the VCS change-bearing producer in its compile-time API. Calling
     * {@link DiffRequestProducer#process} here is unsafe because some producers synchronously query
     * Git and reject the UI thread, so bridge only the producer's existing, side-effect-free
     * {@code getChange()} value.
     */
    private static Optional<Change> requestChange(DiffRequestProducer producer) {
        try {
            return Optional.ofNullable(producer.getClass().getMethod("getChange").invoke(producer))
                    .filter(Change.class::isInstance)
                    .map(Change.class::cast);
        } catch (ReflectiveOperationException error) {
            return Optional.empty();
        }
    }

    /** Content revisions remain attached even when a gutter-local context omits VCS data keys. */
    private static Optional<HunkReviewInput> requestInput(DiffRequest request) {
        if (!(request instanceof ContentDiffRequest contentRequest)
                || contentRequest.getContents().size() != 2) {
            return Optional.empty();
        }
        var contents = contentRequest.getContents();
        var before = contentRevision(contents.get(0));
        var after = contentRevision(contents.get(1));
        return inputFromEndpoints(before, after, isCurrentRevision(contents.get(1)));
    }

    private static Optional<String> contentRevision(DiffContent content) {
        var value = VCS_REVISION_INFO.map(content::getUserData).orElse(null);
        if (!(value instanceof Pair<?, ?> info)
                || !(info.getSecond() instanceof VcsRevisionNumber revision)) {
            return Optional.empty();
        }
        return concreteRevision(revision);
    }

    /** This VCS implementation key is present at runtime but omitted from the public compile API. */
    private static Optional<Key<?>> vcsRevisionInfoKey() {
        try {
            return Optional.ofNullable(
                            Class.forName("com.intellij.diff.DiffVcsDataKeys")
                                    .getField("REVISION_INFO")
                                    .get(null)
                    )
                    .filter(Key.class::isInstance)
                    .map(Key.class::cast);
        } catch (ReflectiveOperationException | LinkageError error) {
            return Optional.empty();
        }
    }

    private static Optional<HunkReviewInput> changeInput(Change change) {
        var before = concreteRevision(change.getBeforeRevision());
        var after = concreteRevision(change.getAfterRevision());
        return inputFromEndpoints(before, after, change.getAfterRevision() != null);
    }

    private static Optional<HunkReviewInput> inputFromEndpoints(
            Optional<String> before,
            Optional<String> after,
            boolean currentAfter
    ) {
        if (before.isEmpty()) {
            return Optional.empty();
        }
        if (after.isPresent()) {
            return Optional.of(new HunkReviewInput.RevisionRange(before.get(), after.get()));
        }
        return currentAfter
                ? Optional.of(new HunkReviewInput.RevisionToWorkingTree(before.get()))
                : Optional.empty();
    }

    private static boolean isCurrentRevision(DiffContent content) {
        var value = VCS_REVISION_INFO.map(content::getUserData).orElse(null);
        return value instanceof Pair<?, ?> info
                && info.getSecond() == VcsRevisionNumber.NULL;
    }

    private static Optional<String> concreteRevision(ContentRevision revision) {
        if (revision == null) {
            return Optional.empty();
        }
        var number = revision.getRevisionNumber();
        if (number == null || number == VcsRevisionNumber.NULL) {
            return Optional.empty();
        }
        return Optional.ofNullable(number.asString()).map(String::trim).filter(value -> !value.isEmpty());
    }

    private static Optional<String> concreteRevision(VcsRevisionNumber number) {
        if (number == null || number == VcsRevisionNumber.NULL) {
            return Optional.empty();
        }
        return Optional.ofNullable(number.asString()).map(String::trim).filter(value -> !value.isEmpty());
    }

    private static VirtualFile contentFile(DiffContent content) {
        return switch (content) {
            case FileContent fileContent -> fileContent.getFile();
            case DocumentContent documentContent -> documentContent.getHighlightFile();
            case null, default -> null;
        };
    }

    @SafeVarargs
    private static <T> Optional<T> firstPresent(T... values) {
        return Arrays.stream(values).filter(Objects::nonNull).findFirst();
    }

    private static int focusedLine(Editor editor) {
        var selection = editor.getSelectionModel();
        var offset = selection.hasSelection()
                ? selection.getSelectionStart()
                : editor.getCaretModel().getOffset();
        var document = editor.getDocument();
        return document.getLineNumber(Math.clamp(offset, 0, document.getTextLength())) + 1;
    }

    /**
     * Gutter popup actions belong to the line under the pointer, which need not be the caret line.
     * JetBrains publishes that zero-based line explicitly in the action data context.
     */
    private static int focusedLine(DataContext context, Editor editor) {
        var gutterLine = EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR.getData(context);
        return actionLine(gutterLine, focusedLine(editor));
    }

    static int actionLine(Integer zeroBasedGutterLine, int caretLine) {
        return zeroBasedGutterLine != null && zeroBasedGutterLine >= 0
                ? zeroBasedGutterLine + 1
                : caretLine;
    }

    private static TargetResolution unavailable(String reason) {
        return new TargetResolution.Unavailable(reason);
    }

    private sealed interface SideResolution {
        record Resolved(ReviewSide side) implements SideResolution {
        }

        record Unavailable(String reason) implements SideResolution {
        }
    }
}
