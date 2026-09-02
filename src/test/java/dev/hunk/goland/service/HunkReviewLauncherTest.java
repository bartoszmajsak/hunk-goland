package dev.hunk.goland.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HunkReviewLauncherTest {
    @Test
    void usesABoundedFirstLineFragmentForWrappedMarkdownConfirmation() {
        var body = "**Please keep this deliberately long reviewer comment readable even when "
                + "the hidden terminal wraps it across several rows.**\n\nFollow-up paragraph.";

        assertThat(HunkReviewLauncher.confirmationFragment(body))
                .isEqualTo("**Please keep this deliberately ")
                .hasSize(32);
    }

    @Test
    void boundsConfirmationByCodePointsRatherThanUtf16Units() {
        var body = "🙂".repeat(40);

        assertThat(HunkReviewLauncher.confirmationFragment(body))
                .isEqualTo("🙂".repeat(32))
                .hasSize(64);
    }

    @Test
    void skipsBlankAndIndentedLeadingLines() {
        assertThat(HunkReviewLauncher.confirmationFragment("\n\n    indented detail\nnext"))
                .isEqualTo("indented detail");
    }
}
