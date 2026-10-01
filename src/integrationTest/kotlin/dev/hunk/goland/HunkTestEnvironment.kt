package dev.hunk.goland

import java.nio.file.Path

/** Keep authentication files private to each test without disconnecting its Wayland display. */
internal fun hunkTestEnvironment(runtimeDirectory: Path): Map<String, String> = buildMap {
    addWaylandDisplay()
    put("XDG_RUNTIME_DIR", runtimeDirectory.toString())
}

private fun MutableMap<String, String>.addWaylandDisplay() {
    val display = System.getenv("WAYLAND_DISPLAY") ?: return
    val path = Path.of(display)
    val runtime = System.getenv("XDG_RUNTIME_DIR")
    put("WAYLAND_DISPLAY", if (path.isAbsolute || runtime == null) display else Path.of(runtime).resolve(path).toString())
}
