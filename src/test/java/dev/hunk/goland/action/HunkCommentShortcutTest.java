package dev.hunk.goland.action;

import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HunkCommentShortcutTest {
    @Test
    void submitsAndConsumesControlEnter() {
        var component = new JTextArea();
        var submissions = new AtomicInteger();
        HunkCommentShortcut.install(component, submissions::incrementAndGet);

        var event = keyPressed(component, InputEvent.CTRL_DOWN_MASK);
        Arrays.stream(component.getKeyListeners()).forEach(listener -> listener.keyPressed(event));

        assertThat(event.isConsumed()).isTrue();
        assertThat(submissions).hasValue(1);
    }

    @Test
    void leavesPlainEnterForMultilineEditing() {
        var component = new JTextArea();
        var submissions = new AtomicInteger();
        HunkCommentShortcut.install(component, submissions::incrementAndGet);

        var event = keyPressed(component, 0);
        Arrays.stream(component.getKeyListeners()).forEach(listener -> listener.keyPressed(event));

        assertThat(event.isConsumed()).isFalse();
        assertThat(submissions).hasValue(0);
    }

    private static KeyEvent keyPressed(JTextArea component, int modifiers) {
        return new KeyEvent(
                component,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                modifiers,
                KeyEvent.VK_ENTER,
                '\n'
        );
    }
}
