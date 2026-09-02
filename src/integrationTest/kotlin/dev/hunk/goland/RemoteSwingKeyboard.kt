package dev.hunk.goland

import com.intellij.driver.client.Remote
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.components.UiComponent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

@Remote("java.awt.AWTEvent")
private interface AwtEventRef

@Remote("java.awt.event.KeyEvent")
private interface KeyEventRef : AwtEventRef

@Remote("java.awt.Component")
private interface EventTargetRef {
    fun dispatchEvent(event: AwtEventRef)
}

/** Dispatch Ctrl+Enter inside the IDE process, independent of compositor-owned input focus. */
fun UiComponent.pressControlEnterInIde() {
    pressEnterInIde(InputEvent.CTRL_DOWN_MASK)
}

/** Dispatch Enter inside the IDE process, independent of compositor-owned input focus. */
fun UiComponent.pressEnterInIde() {
    pressEnterInIde(0)
}

private fun UiComponent.pressEnterInIde(modifiers: Int) {
    driver.withContext<Unit>(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
        val target = cast(component, EventTargetRef::class)
        val event = new(
            KeyEventRef::class,
            target,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            KeyEvent.VK_ENTER,
            '\n',
        )
        target.dispatchEvent(event)
    }
}
