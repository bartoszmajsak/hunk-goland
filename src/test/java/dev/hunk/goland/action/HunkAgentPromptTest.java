package dev.hunk.goland.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HunkAgentPromptTest {
    @Test
    void matchesUpstreamHunkAgentSkillPrompt() {
        assertThat(HunkAgentPrompt.text()).isEqualTo(
                "Load the Hunk skill and use it for this review. "
                        + "Run `hunk skill path` to get the skill path."
        );
    }
}
