package dev.hunk.goland

import com.intellij.driver.client.Remote
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.components.UiComponent

@Remote("javax.swing.JEditorPane")
private interface EditorPaneRef {
    fun getText(): String
}

/** Reads the exact HTML document rendered by an IDE-side Swing editor pane. */
fun UiComponent.htmlInIde(): String =
    driver.withContext(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
        cast(component, EditorPaneRef::class).getText()
    }
