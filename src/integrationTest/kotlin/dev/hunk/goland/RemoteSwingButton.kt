package dev.hunk.goland

import com.intellij.driver.client.Remote
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.components.elements.JButtonUiComponent

@Remote("javax.swing.AbstractButton")
private interface AbstractButtonRef {
    fun doClick()
}

/** Invoke Swing's action on the IDE's EDT; reliable without compositor-owned test focus. */
fun JButtonUiComponent.doClickInIde() {
    driver.withContext<Unit>(OnDispatcher.EDT, LockSemantics.NO_LOCK) {
        cast(component, AbstractButtonRef::class).doClick()
    }
}
