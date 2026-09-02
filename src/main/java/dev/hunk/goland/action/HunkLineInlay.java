package dev.hunk.goland.action;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.ComponentInlayAlignment;
import com.intellij.openapi.editor.ComponentInlayKt;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayProperties;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import dev.hunk.goland.model.HunkSessionNote;
import dev.hunk.goland.model.ReviewTarget;
import dev.hunk.goland.service.HunkReviewLauncher;
import dev.hunk.goland.service.HunkReviewerIdentity;
import dev.hunk.goland.service.HunkSessionException;
import dev.hunk.goland.service.HunkSessionService;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Icon;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Interactive Hunk review thread inserted directly beneath an editor or diff line. */
final class HunkLineInlay implements Disposable {
    private static final Logger LOG = Logger.getInstance(HunkLineInlay.class);
    private static final String NOTIFICATION_GROUP = "Hunk Session";
    private static final Icon THREAD_ICON = icon("/icons/hunkMixedThread.svg");
    private static final Icon COMMENT_ICON = icon("/icons/hunkReviewComment.svg");
    private static final Icon AGENT_ICON = icon("/icons/hunkAgentResponse.svg");
    private static final Icon DELETE_ICON = icon("/icons/hunkDelete.svg");
    private static final Icon CHEVRON_DOWN = icon("/icons/hunkChevronDown.svg");
    private static final Icon CHEVRON_RIGHT = icon("/icons/hunkChevronRight.svg");
    private static final Color SURFACE = new JBColor(0xF7F9FC, 0x24262A);
    private static final Color CARD = new JBColor(0xFFFFFF, 0x2B2D31);
    private static final Color COMMENT_TINT = new JBColor(0xF6FAFF, 0x292F38);
    private static final Color AGENT_TINT = new JBColor(0xFAF8FF, 0x302C3A);
    private static final Color OUTLINE = new JBColor(0xD9E0E8, 0x43464D);
    private static final Color COMMENT_ACCENT = new JBColor(0x2468B2, 0x76A9FA);
    private static final Color AGENT_ACCENT = new JBColor(0x7656B7, 0xB69CFF);
    private static final DateTimeFormatter NOTE_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm");
    private static final int COMPACT_MESSAGES_HEIGHT = 178;
    private static final int EXPANDED_MESSAGES_HEIGHT = 320;
    private static final int UNAVAILABLE_MESSAGES_HEIGHT = 142;
    private static ActiveThread active;

    private final Project project;
    private final Editor editor;
    private final ReviewTarget target;
    private final int editorLine;
    private final HunkSessionService service;
    private final HunkReviewLauncher launcher;
    private final JBPanel<?> notes = new JBPanel<>();
    private final JBTextArea body = new JBTextArea(4, 20);
    private final JButton submit = new JButton("Add comment");
    private final JBLabel status = secondaryLabel("Connecting…");
    private final JBLabel noteCount = secondaryLabel("Loading…");
    private final Set<String> collapsedNotes = new HashSet<>();
    private final Set<String> pendingDeletions = new HashSet<>();
    private final Map<String, RenderedMarkdown> renderedNotes = new HashMap<>();
    private List<HunkSessionNote> lastRenderedNotes;
    private JBScrollPane noteScroll;
    private JBScrollPane composerScroll;
    private JComponent composerCard;
    private JComponent targetErrorPanel;
    private JButton startReviewButton;
    private Inlay<?> inlay;
    private boolean disposed;
    private boolean commentTargetReady;
    private boolean reviewStartAttempted;
    private boolean reviewStarting;
    private boolean revealAfterInitialLoad = true;
    private boolean composerExpanded;
    private boolean messagesExpanded;
    private boolean composerAvailable;
    private String reviewerName = "Reviewer";

    private HunkLineInlay(Project project, Editor editor, ReviewTarget target, int editorLine) {
        this.project = project;
        this.editor = editor;
        this.target = target;
        this.editorLine = editorLine;
        this.service = project.getService(HunkSessionService.class);
        this.launcher = project.getService(HunkReviewLauncher.class);
    }

    static void show(Project project, Editor editor, ReviewTarget target, int editorLine) {
        var current = active;
        var togglingVisibleThread = current != null
                && current.inlay().isValid()
                && current.editor() == editor
                && current.target().equals(target)
                && current.editorLine() == editorLine;
        closeActive();
        if (togglingVisibleThread) {
            return;
        }
        new HunkLineInlay(project, editor, target, editorLine).show();
    }

    private void show() {
        var line = Math.clamp(editorLine - 1, 0, editor.getDocument().getLineCount() - 1);
        var offset = editor.getDocument().getLineEndOffset(line);
        var properties = new InlayProperties()
                .relatesToPrecedingText(true)
                .showAbove(false)
                .priority(10_000)
                .showWhenFolded(true);

        inlay = ComponentInlayKt.addComponentInlay(
                editor,
                offset,
                properties,
                buildContent(),
                ComponentInlayAlignment.FIT_VIEWPORT_X_SPAN
        );
        if (inlay == null) {
            notifyProject("Could not attach the Hunk review thread to this line.", NotificationType.ERROR);
            return;
        }

        active = new ActiveThread(this, editor, target, editorLine, inlay);
        Disposer.register(inlay, this);
        resolveReviewerName();

        project.getService(HunkReviewGutterService.class).watchLine(
                target,
                this,
                loaded -> {
                    if (isOpen()) {
                        renderNotes(loaded);
                    }
                },
                error -> {
                    if (isOpen()) {
                        renderLoadError(message(error));
                    }
                }
        );
        validateTarget();
    }

    private JComponent buildContent() {
        notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
        notes.setOpaque(false);
        notes.setBorder(JBUI.Borders.empty(2));
        renderLoading();

        noteScroll = new JBScrollPane(notes);
        noteScroll.setBorder(JBUI.Borders.empty());
        noteScroll.setOpaque(false);
        noteScroll.getViewport().setOpaque(false);
        noteScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        updateMessageAreaHeight();
        noteScroll.getAccessibleContext().setAccessibleName("Hunk thread messages");

        body.setRows(3);
        body.setFont(new JBLabel().getFont());
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setToolTipText("Markdown supported. Press Ctrl+Enter to add this comment to Hunk.");
        body.getAccessibleContext().setAccessibleName("Hunk review comment");
        body.setEnabled(false);
        HunkCommentShortcut.install(body, this::submitNote);
        composerScroll = new JBScrollPane(body);
        composerScroll.setPreferredSize(new Dimension(JBUI.scale(220), JBUI.scale(86)));

        submit.getAccessibleContext().setAccessibleName("Add comment");
        submit.getAccessibleContext().setAccessibleDescription("Add comment (Ctrl+Enter)");
        submit.setToolTipText("Add comment (Ctrl+Enter)");
        submit.setEnabled(false);
        submit.addActionListener(ignored -> submitNote());
        var copyAgentPrompt = linkButton("Copy agent prompt", JBColor.foreground());
        copyAgentPrompt.setIcon(AllIcons.Actions.Copy);
        copyAgentPrompt.setIconTextGap(JBUI.scale(5));
        copyAgentPrompt.setToolTipText(
                "Copy Hunk's agent-skill setup prompt to the clipboard."
        );
        copyAgentPrompt.getAccessibleContext().setAccessibleName("Copy agent prompt");
        copyAgentPrompt.addActionListener(ignored -> {
            HunkAgentPrompt.copyToClipboard();
            showStatus("Copied agent prompt to clipboard.");
        });
        var actions = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        actions.setOpaque(false);
        actions.add(copyAgentPrompt, BorderLayout.WEST);
        actions.add(submit, BorderLayout.EAST);

        var composerTitle = new JBLabel("Add a comment");
        composerTitle.setFont(composerTitle.getFont().deriveFont(Font.BOLD));
        var composerHint = secondaryLabel("Markdown supported · Ctrl+Enter to add · saved to this line in Hunk");
        var composerHeading = verticalStack(composerTitle, composerHint);
        var resizeComposer = compactIconButton(AllIcons.Actions.Expandall, 22);
        updateComposerResizeButton(resizeComposer);
        resizeComposer.addActionListener(ignored -> {
            composerExpanded = !composerExpanded;
            composerScroll.setPreferredSize(new Dimension(
                    JBUI.scale(220),
                    JBUI.scale(composerExpanded ? 180 : 86)
            ));
            updateComposerResizeButton(resizeComposer);
            composerCard.revalidate();
            composerCard.repaint();
            updateInlaySize();
        });
        var composerHeader = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        composerHeader.setOpaque(false);
        composerHeader.add(composerHeading, BorderLayout.CENTER);
        composerHeader.add(resizeComposer, BorderLayout.EAST);

        composerCard = new RoundedPanel(CARD, OUTLINE, 12);
        composerCard.setLayout(new BorderLayout(0, JBUI.scale(7)));
        composerCard.setBorder(JBUI.Borders.empty(9, 10));
        composerCard.add(composerHeader, BorderLayout.NORTH);
        composerCard.add(composerScroll, BorderLayout.CENTER);
        composerCard.add(actions, BorderLayout.SOUTH);
        composerCard.setVisible(false);

        var close = iconButton("×");
        close.setToolTipText("Close inline Hunk review");
        close.getAccessibleContext().setAccessibleName("Close Hunk line review");
        close.addActionListener(ignored -> closeActive());

        var heading = new JBLabel("Review thread", THREAD_ICON, SwingConstants.LEADING);
        heading.setIconTextGap(JBUI.scale(7));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        var location = secondaryLabel(target.displayName());
        var headingStack = verticalStack(heading, location);
        var trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
        trailing.setOpaque(false);
        trailing.add(noteCount);
        var resizeMessages = compactIconButton(AllIcons.Actions.Expandall, 22);
        updateMessagesResizeButton(resizeMessages);
        resizeMessages.addActionListener(ignored -> {
            messagesExpanded = !messagesExpanded;
            updateMessageAreaHeight();
            updateMessagesResizeButton(resizeMessages);
            refreshNotesPanel();
        });
        trailing.add(resizeMessages);
        trailing.add(close);

        var header = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        header.setOpaque(false);
        header.add(headingStack, BorderLayout.CENTER);
        header.add(trailing, BorderLayout.EAST);

        status.setBorder(JBUI.Borders.emptyLeft(26));
        var navigation = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0));
        navigation.setOpaque(false);
        navigation.add(threadNavigationButton(HunkReviewIndex.Direction.PREVIOUS));
        navigation.add(threadNavigationButton(HunkReviewIndex.Direction.NEXT));
        navigation.getAccessibleContext().setAccessibleName("Hunk thread navigation");
        var headerMeta = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        headerMeta.setOpaque(false);
        headerMeta.add(status, BorderLayout.CENTER);
        headerMeta.add(navigation, BorderLayout.EAST);
        var headerArea = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        headerArea.setOpaque(false);
        headerArea.add(header, BorderLayout.NORTH);
        headerArea.add(headerMeta, BorderLayout.SOUTH);

        var content = new RoundedPanel(SURFACE, OUTLINE, 14);
        content.setLayout(new BorderLayout(0, JBUI.scale(9)));
        content.setBorder(JBUI.Borders.empty(10, 12));
        content.getAccessibleContext().setAccessibleName("Hunk line review");
        content.add(headerArea, BorderLayout.NORTH);
        content.add(noteScroll, BorderLayout.CENTER);
        content.add(composerCard, BorderLayout.SOUTH);
        return content;
    }

    private JButton threadNavigationButton(HunkReviewIndex.Direction direction) {
        var previous = direction == HunkReviewIndex.Direction.PREVIOUS;
        var action = previous ? "Previous" : "Next";
        var shortcut = KeymapUtil.getFirstKeyboardShortcutText(
                HunkThreadNavigationAction.actionId(direction)
        );
        var shortcutText = shortcut.isBlank() ? "" : "  " + shortcut;
        var label = previous
                ? "‹ " + action + shortcutText
                : action + shortcutText + " ›";
        var button = linkButton(label, JBColor.GRAY);
        var accessibleName = action + " Hunk comment thread";
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.setToolTipText(shortcut.isBlank()
                ? accessibleName
                : accessibleName + " (" + shortcut + ")");
        button.addActionListener(ignored -> navigateThread(direction));
        return button;
    }

    private void navigateThread(HunkReviewIndex.Direction direction) {
        var index = project.getService(HunkReviewGutterService.class).currentIndex(target);
        var destination = index.adjacentTo(target, direction);
        if (destination == null) {
            showStatus("No Hunk comment threads are available in this review yet.");
            return;
        }
        if (index.threads().size() == 1
                && destination.equals(new HunkReviewIndex.ThreadLocation(
                        target.filePath(),
                        target.line()
                ))) {
            showStatus("This is the only Hunk comment thread in the review.");
            return;
        }
        HunkThreadNavigator.open(project, index, destination, null);
    }

    private void validateTarget() {
        commentTargetReady = false;
        if (targetErrorPanel != null) {
            notes.remove(targetErrorPanel);
        }
        targetErrorPanel = null;
        setComposerAvailable(false);
        showStatus("Connecting…");
        LOG.info("event=comment-target-validation-start target=" + target.displayName()
                + " root=" + target.repositoryRoot()
                + " input=" + target.reviewInput().command());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                service.validateCommentTarget(target);
                LOG.info("event=comment-target-validation-succeeded target=" + target.displayName()
                        + " root=" + target.repositoryRoot()
                        + " input=" + target.reviewInput().command());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isOpen()) {
                        return;
                    }
                    commentTargetReady = true;
                    if (targetErrorPanel != null) {
                        notes.remove(targetErrorPanel);
                        targetErrorPanel = null;
                    }
                    setComposerAvailable(true);
                    if ("Connecting…".equals(status.getText())) {
                        showStatus("");
                    }
                    if (revealAfterInitialLoad) {
                        revealAfterInitialLoad = false;
                        revealComposer();
                    }
                });
            } catch (HunkSessionException error) {
                LOG.warn("event=comment-target-validation-failed target=" + target.displayName()
                        + " root=" + target.repositoryRoot()
                        + " input=" + target.reviewInput().command()
                        + " error=" + message(error));
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isOpen()) {
                        renderTargetError(message(error));
                    }
                });
            }
        });
    }

    private void submitNote() {
        if (!body.isEnabled() || !submit.isEnabled()) {
            return;
        }
        var text = body.getText().trim();
        if (text.isEmpty()) {
            showError("Enter a review comment first.");
            return;
        }

        submit.setEnabled(false);
        body.setEnabled(false);
        showStatus("Submitting comment…");
        LOG.info("event=comment-submit-start"
                + " target=" + target.displayName()
                + " bodyChars=" + text.length());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                service.addReviewComment(target, text);
                LOG.info("event=comment-submit-succeeded"
                        + " target=" + target.displayName()
                        + " bodyChars=" + text.length());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isOpen()) {
                        return;
                    }
                    body.setText("");
                    body.setEnabled(true);
                    submit.setEnabled(true);
                    showStatus("Comment submitted on " + target.displayName() + ".");
                    notifyProject("Added review comment on " + target.displayName() + ".", NotificationType.INFORMATION);
                    project.getService(HunkReviewGutterService.class).refreshSoon();
                });
            } catch (HunkSessionException error) {
                LOG.warn("event=comment-submit-failed"
                        + " target=" + target.displayName()
                        + " bodyChars=" + text.length()
                        + " error=" + message(error), error);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isOpen()) {
                        return;
                    }
                    body.setEnabled(true);
                    submit.setEnabled(true);
                    showError(message(error));
                });
            }
        });
    }

    private void renderLoading() {
        lastRenderedNotes = null;
        setComposerAvailable(false);
        notes.removeAll();
        notes.add(secondaryLabel("Loading comments and agent responses…"));
    }

    private void renderNotes(List<HunkSessionNote> loaded) {
        var ordered = chronologicalNotes(loaded);
        if (ordered.equals(lastRenderedNotes)) {
            return;
        }
        var scrollPosition = ScrollPosition.capture(noteScroll);
        lastRenderedNotes = ordered;
        notes.removeAll();
        var liveNoteIds = ordered.stream().map(HunkSessionNote::id).toList();
        collapsedNotes.retainAll(liveNoteIds);
        pendingDeletions.retainAll(liveNoteIds);
        renderedNotes.keySet().retainAll(liveNoteIds);
        if (ordered.isEmpty()) {
            var empty = secondaryLabel("No comments on this line yet.");
            empty.setBorder(JBUI.Borders.empty(8, 4));
            notes.add(empty);
        } else {
            for (var index = 0; index < ordered.size(); index++) {
                notes.add(noteCard(ordered.get(index)));
                if (index < ordered.size() - 1) {
                    notes.add(Box.createVerticalStrut(JBUI.scale(9)));
                }
            }
        }
        if (targetErrorPanel != null) {
            notes.add(targetErrorPanel);
        }
        noteCount.setText(countLabel(ordered));
        if (commentTargetReady && "Connecting…".equals(status.getText())) {
            showStatus("");
        }
        refreshNotesPanel();
        if (revealAfterInitialLoad && commentTargetReady) {
            revealAfterInitialLoad = false;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (isOpen()) {
                    revealComposer();
                }
            });
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isOpen()) {
                scrollPosition.restore(noteScroll);
            }
        });
    }

    private JComponent noteCard(HunkSessionNote note) {
        var reviewerComment = note.isReviewerComment();
        var title = reviewerComment ? "Comment" : "Agent response";
        var accent = reviewerComment ? COMMENT_ACCENT : AGENT_ACCENT;
        var heading = new JBLabel(
                title,
                reviewerComment ? COMMENT_ICON : AGENT_ICON,
                SwingConstants.LEADING
        );
        heading.setIconTextGap(JBUI.scale(6));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setForeground(accent);
        var metadata = secondaryLabel(noteMetadata(note));
        metadata.setBorder(JBUI.Borders.emptyTop(2));
        metadata.getAccessibleContext().setAccessibleName(
                title + " author: " + displayAuthor(note)
        );
        var headingStack = verticalStack(heading, metadata);

        var text = markdownBody(note, title);

        var expanded = !collapsedNotes.contains(note.id());
        text.setVisible(expanded);
        var toggle = disclosureButton(note, expanded, text);

        var leading = new JPanel(new BorderLayout(JBUI.scale(1), 0));
        leading.setOpaque(false);
        leading.add(toggle, BorderLayout.WEST);
        leading.add(headingStack, BorderLayout.CENTER);

        var header = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        header.setOpaque(false);
        header.add(leading, BorderLayout.CENTER);
        if (reviewerComment && note.editable()) {
            header.add(deleteControls(note), BorderLayout.EAST);
        }

        var card = new NaturalHeightRoundedPanel(
                reviewerComment ? COMMENT_TINT : AGENT_TINT,
                OUTLINE,
                12
        );
        card.setLayout(new BorderLayout(0, JBUI.scale(6)));
        card.setBorder(JBUI.Borders.empty(10, 12));
        card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        card.getAccessibleContext().setAccessibleName(title + " card");
        card.add(header, BorderLayout.NORTH);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    private JButton disclosureButton(HunkSessionNote note, boolean expanded, JComponent content) {
        var button = compactIconButton(expanded ? CHEVRON_DOWN : CHEVRON_RIGHT, 18);
        updateDisclosureAccessibility(button, note, expanded);
        button.addActionListener(ignored -> {
            var nextExpanded = !content.isVisible();
            content.setVisible(nextExpanded);
            button.setIcon(nextExpanded ? CHEVRON_DOWN : CHEVRON_RIGHT);
            if (nextExpanded) {
                collapsedNotes.remove(note.id());
            } else {
                collapsedNotes.add(note.id());
            }
            updateDisclosureAccessibility(button, note, nextExpanded);
            refreshNotesPanel();
        });
        return button;
    }

    private JComponent markdownBody(HunkSessionNote note, String title) {
        var pane = new JEditorPane();
        pane.setContentType("text/html");
        var editorKit = (HTMLEditorKit) pane.getEditorKit();
        editorKit.getStyleSheet().addRule("body { margin: 0; padding: 0; }");
        editorKit.getStyleSheet().addRule("p { margin-top: 0; margin-bottom: 6px; }");
        editorKit.getStyleSheet().addRule("ul, ol, pre { margin-top: 4px; margin-bottom: 6px; }");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setFont(new JBLabel().getFont());
        pane.setForeground(JBColor.foreground());
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setFocusable(true);
        pane.setBorder(JBUI.Borders.empty(4, 22, 3, 5));
        pane.getAccessibleContext().setAccessibleName(title + " body");
        var cached = renderedNotes.get(note.id());
        if (cached == null || !cached.markdown().equals(note.body())) {
            cached = new RenderedMarkdown(note.body(), HunkMarkdown.render(note.body()));
            renderedNotes.put(note.id(), cached);
        }
        pane.setText(cached.html());
        pane.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openLink(event.getDescription());
            }
        });
        return pane;
    }

    private void openLink(String value) {
        if (value == null) {
            return;
        }
        try {
            var uri = new URI(value);
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                BrowserUtil.browse(uri);
            }
        } catch (URISyntaxException ignored) {
            // CommonMark safe links already reject malformed destinations; stay inert if one
            // nevertheless reaches Swing's hyperlink callback.
        }
    }

    private void updateDisclosureAccessibility(
            JButton button,
            HunkSessionNote note,
            boolean expanded
    ) {
        var subject = note.isReviewerComment() ? "comment" : "agent response";
        var author = displayAuthor(note);
        var action = expanded ? "Collapse" : "Expand";
        var name = action + " " + subject + " from " + author;
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
    }

    private JComponent deleteControls(HunkSessionNote note) {
        var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0));
        controls.setOpaque(false);
        var remove = iconButton(DELETE_ICON);
        remove.setToolTipText("Delete comment");
        var cancel = linkButton("Cancel", JBColor.foreground());
        var confirm = new JButton("Delete comment");
        remove.getAccessibleContext().setAccessibleName("Delete review comment");
        cancel.getAccessibleContext().setAccessibleName("Cancel comment deletion");
        confirm.getAccessibleContext().setAccessibleName("Confirm delete review comment");
        var confirming = pendingDeletions.contains(note.id());
        remove.setVisible(!confirming);
        cancel.setVisible(confirming);
        confirm.setVisible(confirming);

        remove.addActionListener(ignored -> {
            pendingDeletions.add(note.id());
            remove.setVisible(false);
            cancel.setVisible(true);
            confirm.setVisible(true);
            controls.revalidate();
            controls.repaint();
            updateInlaySize();
        });
        cancel.addActionListener(ignored -> {
            pendingDeletions.remove(note.id());
            cancel.setVisible(false);
            confirm.setVisible(false);
            remove.setVisible(true);
            controls.revalidate();
            controls.repaint();
            updateInlaySize();
        });
        confirm.addActionListener(ignored -> deleteComment(note, controls));
        controls.add(remove);
        controls.add(cancel);
        controls.add(confirm);
        return controls;
    }

    private void deleteComment(HunkSessionNote note, JComponent controls) {
        controls.setEnabled(false);
        Arrays.stream(controls.getComponents()).forEach(component -> component.setEnabled(false));
        showStatus("Deleting comment…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                service.removeReviewComment(target, note.id());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isOpen()) {
                        return;
                    }
                    pendingDeletions.remove(note.id());
                    showStatus("Comment deleted.");
                    project.getService(HunkReviewGutterService.class).refreshSoon();
                });
            } catch (HunkSessionException error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isOpen()) {
                        return;
                    }
                    controls.setEnabled(true);
                    Arrays.stream(controls.getComponents()).forEach(component -> component.setEnabled(true));
                    showError(message(error));
                });
            }
        });
    }

    private void renderLoadError(String value) {
        lastRenderedNotes = null;
        notes.removeAll();
        targetErrorPanel = null;
        noteCount.setText(canStartMatchingReview(value) ? "Setup needed" : "Unavailable");
        updateMessageAreaHeight();
        renderTargetError(value);
    }

    /** Keeps already-loaded notes visible when only reviewer-note composition is unavailable. */
    private void renderTargetError(String value) {
        var startAutomatically = canStartMatchingReview(value) && !reviewStartAttempted;
        commentTargetReady = false;
        setComposerAvailable(false);
        if (targetErrorPanel != null) {
            notes.remove(targetErrorPanel);
        }
        var message = new JBTextArea(connectionMessage(value));
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setFont(new JBLabel().getFont());
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        var panel = new RoundedPanel(CARD, OUTLINE, 12);
        panel.setLayout(new BorderLayout(0, JBUI.scale(4)));
        panel.setBorder(JBUI.Borders.empty(7, 8));
        panel.add(message, BorderLayout.CENTER);
        panel.add(recoveryActions(value), BorderLayout.SOUTH);
        targetErrorPanel = panel;
        notes.add(panel);
        if (startAutomatically) {
            startMatchingReview();
        } else if (reviewStarting) {
            showStatus("Starting " + target.reviewInput().commandDisplay() + "…");
        } else if (canStartMatchingReview(value)) {
            showStatus(connectionStatus(value));
        } else {
            showError(connectionStatus(value));
        }
        refreshNotesPanel();
    }

    private JComponent recoveryActions(String value) {
        var retry = new JButton("Retry");
        retry.getAccessibleContext().setAccessibleName("Retry Hunk review connection");
        retry.addActionListener(ignored -> {
            renderLoading();
            validateTarget();
            project.getService(HunkReviewGutterService.class).refreshSoon();
        });
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        actions.setOpaque(false);
        if (canStartMatchingReview(value)) {
            var input = target.reviewInput();
            var startReview = new JButton(input.actionLabel());
            startReviewButton = startReview;
            startReview.getAccessibleContext().setAccessibleName("Start IDE-managed Hunk review");
            startReview.setToolTipText(
                    "Run " + input.commandDisplay() + " in an off-screen PTY owned by GoLand"
            );
            startReview.setEnabled(!reviewStarting);
            startReview.addActionListener(ignored -> startMatchingReview());
            actions.add(startReview);
        } else {
            startReviewButton = null;
        }
        actions.add(retry);
        return actions;
    }

    private void setComposerAvailable(boolean available) {
        composerAvailable = available;
        body.setEnabled(available);
        submit.setEnabled(available);
        if (composerCard != null) {
            composerCard.setVisible(available);
        }
        updateMessageAreaHeight();
        updateInlaySize();
    }

    private void updateMessageAreaHeight() {
        if (noteScroll == null) {
            return;
        }
        var height = messagesExpanded
                ? EXPANDED_MESSAGES_HEIGHT
                : composerAvailable ? COMPACT_MESSAGES_HEIGHT : UNAVAILABLE_MESSAGES_HEIGHT;
        noteScroll.setPreferredSize(new Dimension(JBUI.scale(220), JBUI.scale(height)));
    }

    private void resolveReviewerName() {
        project.getService(HunkReviewerIdentity.class)
                .reviewerName(target.repositoryRoot())
                .thenAccept(resolved -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isOpen() || resolved.equals(reviewerName)) {
                        return;
                    }
                    reviewerName = resolved;
                    var rendered = lastRenderedNotes;
                    lastRenderedNotes = null;
                    if (rendered != null) {
                        renderNotes(rendered);
                    }
                }));
    }

    private String connectionMessage(String value) {
        if (isAmbiguousSession(value)) {
            return "More than one Hunk review is open for this worktree. GoLand is starting and "
                    + "selecting its own matching review so the other sessions can remain open.";
        }
        if (isMissingSession(value)) {
            return "The daemon is running, but no Hunk review is open for this worktree. "
                    + "GoLand is starting the matching review automatically.";
        }
        if (value.startsWith("Could not reach the Hunk session daemon")) {
            return "The Hunk daemon is not reachable. Start a Hunk review or the daemon, then retry.";
        }
        if (value.contains("is not part of a hunk in the active Hunk review")) {
            return "This line is outside the active Hunk diff. Comments are available on published "
                    + "hunk lines; expanded-gap comments are not supported by the IDE yet.";
        }
        if (isReviewMismatch(value)) {
            return "The open Hunk review does not match this GoLand comparison. Expected `"
                    + target.reviewInput().commandDisplay() + "`. GoLand is starting the matching "
                    + "IDE-managed review automatically.";
        }
        if (isUnmanagedReview(value)) {
            return "This Hunk review is readable and its notes can be deleted, but GoLand cannot "
                    + "type into a TUI it does not own. GoLand is starting a separate managed "
                    + "review for genuine reviewer comments; the external TUI can remain open.";
        }
        return "The active Hunk review is unavailable for this line. Fix the connection or review "
                + "selection, then retry.";
    }

    private static String connectionStatus(String value) {
        if (isAmbiguousSession(value)) {
            return "Selecting an IDE-managed Hunk review.";
        }
        if (isMissingSession(value)) {
            return "No review is open for this worktree.";
        }
        if (value.startsWith("Could not reach the Hunk session daemon")) {
            return "Hunk daemon unavailable.";
        }
        if (value.contains("is not part of a hunk in the active Hunk review")) {
            return "Line unavailable in this review.";
        }
        if (isUnmanagedReview(value)) {
            return "External Hunk review is read-only in GoLand.";
        }
        return "Hunk review unavailable.";
    }

    private void startMatchingReview() {
        if (reviewStarting) {
            return;
        }
        reviewStartAttempted = true;
        reviewStarting = true;
        if (startReviewButton != null) {
            startReviewButton.setEnabled(false);
        }
        showStatus("Starting " + target.reviewInput().commandDisplay() + "…");
        LOG.info("event=matching-review-start-request target=" + target.displayName()
                + " root=" + target.repositoryRoot()
                + " input=" + target.reviewInput().command());
        launcher.startReview(target.repositoryRoot(), target.reviewInput())
                .whenComplete((sessionId, error) -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isOpen()) {
                        return;
                    }
                    reviewStarting = false;
                    if (error != null) {
                        LOG.warn("event=matching-review-start-failed target=" + target.displayName()
                                + " root=" + target.repositoryRoot()
                                + " input=" + target.reviewInput().command()
                                + " error=" + errorMessage(error), error);
                        if (startReviewButton != null) {
                            startReviewButton.setEnabled(true);
                        }
                        showError("Could not start the IDE-managed Hunk review: " + errorMessage(error));
                        return;
                    }
                    LOG.info("event=matching-review-start-succeeded target=" + target.displayName()
                            + " root=" + target.repositoryRoot()
                            + " input=" + target.reviewInput().command()
                            + " sessionId=" + sessionId);
                    renderLoading();
                    validateTarget();
                    project.getService(HunkReviewGutterService.class).refreshSoon();
                }));
    }

    private static boolean isMissingSession(String value) {
        return value.startsWith("No active session matches repoRoot");
    }

    static boolean isAmbiguousSession(String value) {
        return value.startsWith("Multiple active sessions match repoRoot");
    }

    private static boolean isReviewMismatch(String value) {
        return value.startsWith("The active Hunk review does not contain ");
    }

    private static boolean isUnmanagedReview(String value) {
        return value.startsWith("This live Hunk review is readable")
                || value.startsWith("This review is not owned by GoLand");
    }

    private boolean canStartMatchingReview(String value) {
        var recoverable = isMissingSession(value)
                || isAmbiguousSession(value)
                || isReviewMismatch(value)
                || isUnmanagedReview(value);
        if (!recoverable) {
            return false;
        }
        // Re-running an already-owned input cannot make a missing file or line appear. Keep the
        // error actionable without replacing a healthy Hunk process in a restart loop.
        return !isReviewMismatch(value) || !launcher.owns(target);
    }

    private static String errorMessage(Throwable error) {
        var current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private void refreshNotesPanel() {
        notes.revalidate();
        notes.repaint();
        updateInlaySize();
    }

    private void updateInlaySize() {
        if (inlay != null && inlay.isValid()) {
            inlay.update();
            editor.getContentComponent().revalidate();
            editor.getContentComponent().repaint();
        }
    }

    private void revealComposer() {
        updateInlaySize();
        var bounds = inlay.getBounds();
        if (bounds != null) {
            var scrolling = editor.getScrollingModel();
            var visible = scrolling.getVisibleArea();
            var overflow = bounds.y + bounds.height + JBUI.scale(12) - (visible.y + visible.height);
            if (overflow > 0) {
                scrolling.scrollVertically(scrolling.getVerticalScrollOffset() + overflow);
            }
        }
        body.requestFocusInWindow();
    }

    private boolean isOpen() {
        return !disposed && inlay != null && inlay.isValid();
    }

    private void showStatus(String value) {
        status.setText(value);
        status.setVisible(!value.isBlank());
        status.setForeground(JBColor.GRAY);
        updateInlaySize();
    }

    private void showError(String value) {
        status.setText(value);
        status.setVisible(true);
        status.setForeground(JBColor.RED);
        updateInlaySize();
    }

    private void notifyProject(String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(message, type)
                .notify(project);
    }

    private static void closeActive() {
        var current = active;
        active = null;
        if (current == null) {
            return;
        }
        if (current.inlay().isValid()) {
            Disposer.dispose(current.inlay());
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        if (active != null && active.thread() == this) {
            active = null;
        }
    }

    private static String message(HunkSessionException error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static String countLabel(List<HunkSessionNote> loaded) {
        if (loaded.isEmpty()) {
            return "No comments yet";
        }
        return loaded.size() + (loaded.size() == 1 ? " item" : " items");
    }

    static List<HunkSessionNote> chronologicalNotes(List<HunkSessionNote> notes) {
        return notes.stream()
                .sorted(Comparator.comparing(
                        note -> timestamp(note.createdAt()),
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
    }

    private static Instant timestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String noteMetadata(HunkSessionNote note) {
        var values = new ArrayList<String>();
        values.add(displayAuthor(note));
        formatTime(note.createdAt()).ifPresent(values::add);
        return String.join(" · ", values);
    }

    private String displayAuthor(HunkSessionNote note) {
        return HunkReviewerIdentity.displayAuthor(note, reviewerName);
    }

    private static java.util.Optional<String> formatTime(String value) {
        return java.util.Optional.ofNullable(timestamp(value))
                .map(instant -> NOTE_TIME.format(instant.atZone(ZoneId.systemDefault())));
    }

    private void updateComposerResizeButton(JButton button) {
        var action = composerExpanded ? "Use compact comment editor" : "Expand comment editor";
        button.setIcon(composerExpanded ? AllIcons.Actions.Collapseall : AllIcons.Actions.Expandall);
        button.setToolTipText(action);
        button.getAccessibleContext().setAccessibleName(action);
    }

    private void updateMessagesResizeButton(JButton button) {
        var action = messagesExpanded ? "Use compact thread messages" : "Expand thread messages";
        button.setIcon(messagesExpanded ? AllIcons.Actions.Collapseall : AllIcons.Actions.Expandall);
        button.setToolTipText(action);
        button.getAccessibleContext().setAccessibleName(action);
    }

    private static JPanel verticalStack(JComponent... components) {
        var panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        Arrays.stream(components).forEach(component -> {
            component.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            panel.add(component);
        });
        return panel;
    }

    private static JBLabel secondaryLabel(String text) {
        var label = new JBLabel(text);
        label.setForeground(JBColor.GRAY);
        label.setFont(label.getFont().deriveFont(Math.max(10f, label.getFont().getSize2D() - 1f)));
        return label;
    }

    private static JButton iconButton(String text) {
        var button = new JButton(text);
        button.setBorder(JBUI.Borders.empty(1, 5));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static JButton iconButton(Icon icon) {
        return compactIconButton(icon, 22);
    }

    private static JButton compactIconButton(Icon icon, int size) {
        var button = iconButton("");
        var dimensions = new Dimension(JBUI.scale(size), JBUI.scale(size));
        button.setIcon(icon);
        button.setBorder(JBUI.Borders.empty());
        button.setPreferredSize(dimensions);
        button.setMinimumSize(dimensions);
        button.setMaximumSize(dimensions);
        return button;
    }

    private static JButton linkButton(String text, Color color) {
        var button = iconButton(text);
        button.setForeground(color);
        return button;
    }

    private static Icon icon(String path) {
        return IconLoader.getIcon(path, HunkLineInlay.class);
    }

    /** Lightweight rounded native surface that follows JetBrains light and dark themes. */
    private static class RoundedPanel extends JPanel {
        private final Color fill;
        private final Color outline;
        private final int arc;

        private RoundedPanel(Color fill, Color outline, int arc) {
            this.fill = fill;
            this.outline = outline;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                var diameter = JBUI.scale(arc);
                copy.setColor(fill);
                copy.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, diameter, diameter);
                copy.setColor(outline);
                copy.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, diameter, diameter);
            } finally {
                copy.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    /** Lets BoxLayout use the viewport width without stretching a short comment vertically. */
    private static final class NaturalHeightRoundedPanel extends RoundedPanel {
        private NaturalHeightRoundedPanel(Color fill, Color outline, int arc) {
            super(fill, outline, arc);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private record RenderedMarkdown(String markdown, String html) {
    }

    private record ScrollPosition(int value, boolean atBottom) {
        private static ScrollPosition capture(JBScrollPane scrollPane) {
            var bar = scrollPane.getVerticalScrollBar();
            var model = bar.getModel();
            return new ScrollPosition(
                    model.getValue(),
                    model.getValue() + model.getExtent() >= model.getMaximum() - JBUI.scale(2)
            );
        }

        private void restore(JBScrollPane scrollPane) {
            var bar = scrollPane.getVerticalScrollBar();
            var model = bar.getModel();
            var maximum = Math.max(model.getMinimum(), model.getMaximum() - model.getExtent());
            bar.setValue(atBottom ? maximum : Math.min(value, maximum));
        }
    }

    private record ActiveThread(
            HunkLineInlay thread,
            Editor editor,
            ReviewTarget target,
            int editorLine,
            Inlay<?> inlay
    ) {
    }
}
