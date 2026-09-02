package dev.hunk.goland.action;

import javax.swing.text.JTextComponent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/** Owns the composer-local submit chord without changing the text component's Enter behavior. */
final class HunkCommentShortcut {
    private HunkCommentShortcut() {
    }

    static void install(JTextComponent component, Runnable submit) {
        component.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER
                        && (event.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                    event.consume();
                    submit.run();
                }
            }
        });
    }
}
