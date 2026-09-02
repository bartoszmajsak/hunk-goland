package dev.hunk.goland.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HunkMarkdownTest {
    @Test
    void rendersCommonReviewFormatting() {
        var html = HunkMarkdown.render("A **clear** note with `context`.\n\n- first\n- second");

        assertThat(html).contains(
                "<strong>clear</strong>",
                "<code>context</code>",
                "<ul>",
                "<li>first</li>"
        );
    }

    @Test
    void keepsRawHtmlAndRemoteImagesInert() {
        var html = HunkMarkdown.render(
                "<script>alert('no')</script> ![tracking](https://example.test/pixel.png)"
        );

        assertThat(html)
                .doesNotContain("<script>", "<img")
                .contains("[image omitted]");
    }

    @Test
    void rejectsUnsafeLinkSchemes() {
        var html = HunkMarkdown.render(
                "[script](javascript:alert('no')) [data](data:text/html,no) [file](file:///tmp/no)"
        );

        assertThat(html).doesNotContainIgnoringCase("javascript:", "data:text", "file:");
    }

    @Test
    void keepsHttpLinksForExplicitClickThrough() {
        var html = HunkMarkdown.render("[review](https://example.test/review)");

        assertThat(html).contains("href=\"https://example.test/review\"");
    }
}
