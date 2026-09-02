package dev.hunk.goland.action;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Emphasis;
import org.commonmark.node.Image;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.DefaultUrlSanitizer;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;

/** Safe CommonMark rendering for review content supplied by people and agents. */
final class HunkMarkdown {
    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .urlSanitizer(new DefaultUrlSanitizer(List.of("http", "https")))
            .build();

    private HunkMarkdown() {
    }

    static String render(String markdown) {
        // Escaping before parsing prevents a raw HTML block from swallowing Markdown later on its
        // line. Renderer escaping is retained as a second boundary for any parsed HTML node.
        var document = PARSER.parse(markdown.replace("<", "&lt;"));
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                // Do not let a Markdown image make the IDE fetch arbitrary remote content.
                var placeholder = new Emphasis();
                placeholder.appendChild(new Text("[image omitted]"));
                image.insertBefore(placeholder);
                image.unlink();
            }
        });
        return RENDERER.render(document);
    }
}
