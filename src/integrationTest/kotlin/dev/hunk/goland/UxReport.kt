package dev.hunk.goland

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.components.UiComponent
import java.awt.Point
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

@Remote("com.jetbrains.performancePlugin.commands.TakeScreenshotCommandKt")
private interface IdeScreenshotCapture {
    fun takeScreenshotOfAllWindowsBlocking(folder: String)
}

@Remote("java.awt.Window")
private interface AwtWindowRef {
    fun getWindows(): List<AwtWindowRef>
    fun getName(): String?
    fun getLocationOnScreen(): Point
    fun getWidth(): Int
    fun getHeight(): Int
    fun isShowing(): Boolean
}

/** Captures reviewable UI artifacts independently of pass/fail-only test diagnostics. */
class UxReport private constructor(
    private val output: Path,
    private val displayBackend: String,
) {
    private val screenshots = mutableListOf<Screenshot>()
    private val requests = mutableListOf<String>()

    fun capture(frame: UiComponent, fileName: String, title: String, description: String) {
        Files.createDirectories(output)
        val path = output.resolve("$fileName.png")
        // The IDE-side capture paints every AWT window independently. Compositing them preserves
        // notifications without depending on X11, Wayland, or a particular desktop compositor.
        frame.robot.waitForIdle()
        Files.deleteIfExists(path)
        captureIdeWindows(frame, path, fileName)
        require(Files.isRegularFile(path)) { "JetBrains Driver did not create $path" }
        require(Files.size(path) > 0) { "JetBrains Driver created an empty screenshot" }
        screenshots.removeIf { it.fileName == path.fileName.toString() }
        screenshots += Screenshot(path.fileName.toString(), title, description)
        write("running")
    }

    fun recordRequest(request: String) {
        requests += request
        write("running")
    }

    fun finish(status: String) {
        write(status)
    }

    private fun captureIdeWindows(frame: UiComponent, target: Path, label: String) {
        val windows = frame.driver.utility(AwtWindowRef::class)
            .getWindows()
            .filter { it.isShowing() && it.getWidth() > 0 && it.getHeight() > 0 }
            .map {
                val location = it.getLocationOnScreen()
                CapturedWindow(
                    name = it.getName() ?: "null",
                    x = location.x,
                    y = location.y,
                    width = it.getWidth(),
                    height = it.getHeight(),
                )
            }
        check(windows.isNotEmpty()) { "JetBrains Driver found no visible IDE windows" }

        val marker = "$label-${System.nanoTime()}"
        val captureRoot = output.resolve(".driver-capture")
        val base = captureRoot.resolve(marker)
        frame.driver.utility(IdeScreenshotCapture::class)
            .takeScreenshotOfAllWindowsBlocking(base.toString())
        val captureDirectory = Files.list(captureRoot).use { entries ->
            entries.filter { it.fileName.toString().endsWith(marker) }
                .findFirst()
                .orElseThrow { IllegalStateException("JetBrains Driver did not write its window captures") }
        }

        try {
            val minX = windows.minOf { it.x }
            val minY = windows.minOf { it.y }
            val maxX = windows.maxOf { it.x + it.width }
            val maxY = windows.maxOf { it.y + it.height }
            val combined = BufferedImage(maxX - minX, maxY - minY, BufferedImage.TYPE_INT_ARGB)
            val graphics = combined.createGraphics()
            try {
                // Paint the main IDE frame first, then smaller notification windows on top.
                windows.sortedByDescending { it.width.toLong() * it.height }.forEach { window ->
                    val imagePath = captureDirectory.resolve("${window.name}.png")
                    if (Files.isRegularFile(imagePath)) {
                        ImageIO.read(imagePath.toFile())?.let { image ->
                            graphics.drawImage(image, window.x - minX, window.y - minY, null)
                        }
                    }
                }
            } finally {
                graphics.dispose()
            }
            check(ImageIO.write(combined, "png", target.toFile())) {
                "No PNG writer is available for $target"
            }
        } finally {
            captureDirectory.toFile().deleteRecursively()
        }
    }

    private fun write(status: String) {
        Files.createDirectories(output)
        val cards = screenshots.joinToString("\n") { screenshot ->
            """
            <article class="card">
              <h2>${escape(screenshot.title)}</h2>
              <p>${escape(screenshot.description)}</p>
              <a href="${escape(screenshot.fileName)}"><img src="${escape(screenshot.fileName)}" alt="${escape(screenshot.title)}"></a>
            </article>
            """.trimIndent()
        }
        val payloads = if (requests.isEmpty()) {
            "<p>No managed-review action was captured.</p>"
        } else {
            requests.joinToString("\n") { "<pre>${escape(it)}</pre>" }
        }
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Hunk GoLand UX report</title>
              <style>
                :root { color-scheme: dark; font-family: Inter, system-ui, sans-serif; background: #111318; color: #e8eaf0; }
                body { max-width: 1500px; margin: 0 auto; padding: 32px; }
                header { margin-bottom: 28px; }
                .status { display: inline-block; padding: 5px 10px; border-radius: 999px; background: #273146; color: #b8d3ff; }
                .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(520px, 1fr)); gap: 22px; }
                .card { background: #1a1d24; border: 1px solid #343946; border-radius: 12px; padding: 18px; }
                .card h2 { margin: 0 0 6px; font-size: 18px; }
                .card p { color: #aeb4c2; min-height: 2.5em; }
                img { width: 100%; height: auto; border: 1px solid #3b414e; border-radius: 7px; }
                pre { white-space: pre-wrap; word-break: break-word; background: #0e1014; padding: 14px; border-radius: 8px; }
                footer { margin-top: 30px; color: #8f96a6; }
              </style>
            </head>
            <body>
              <header>
                <h1>Hunk Session for GoLand — UX review</h1>
                <p class="status">Driver run: ${escape(status)}</p>
                <p>Real GoLand 2026.2 UI driven against a generated, self-contained Go and Git fixture.</p>
                <p>Display adapter: <code>${escape(displayBackend)}</code>.</p>
              </header>
              <main class="grid">$cards</main>
              <section>
                <h2>Managed PTY and native-action evidence</h2>
                <p><code>pty-user-note</code> records what the fixture's Hunk-compatible TUI saved
                after GoLand navigated to the line and drove its native reviewer composer. Native
                daemon operations, such as <code>comment-rm</code>, are shown in their actual
                upstream wire shape. No invented reviewer-note HTTP action is used.</p>
                $payloads
              </section>
              <section>
                <h2>Unmodified upstream Hunk E2E</h2>
                <p>The separate enabled <code>realHunkE2e</code> task launches an installed upstream
                Hunk binary and daemon on an isolated port. It verifies a genuine
                <code>source: "user"</code> note, an externally added
                <code>source: "agent"</code> response, and native deletion. Its machine-readable
                result is <a href="real-daemon-e2e.json">real-daemon-e2e.json</a>.</p>
                <p>The restart/recovery UX is captured separately in
                <a href="recovery/index.html">recovery/index.html</a>.</p>
                <p>The shared Compare-with-Branch/source-editor context is captured separately in
                <a href="cross-view/index.html">cross-view/index.html</a>.</p>
              </section>
              <footer>Images are deterministic test artifacts, not mocked UI renderings.</footer>
            </body>
            </html>
        """.trimIndent()
        Files.writeString(output.resolve("index.html"), html, StandardCharsets.UTF_8)
    }

    private data class Screenshot(
        val fileName: String,
        val title: String,
        val description: String,
    )

    private data class CapturedWindow(
        val name: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    companion object {
        fun configured(): UxReport {
            val output = configuredOutput()
            clearScreenshots(output)
            return UxReport(
                output,
                System.getProperty("hunk.uiDisplayBackend", "native"),
            )
        }

        fun configuredSubreport(directory: String): UxReport {
            require(directory.matches(Regex("[a-z0-9-]+"))) { "Invalid UX subreport name" }
            val output = configuredOutput().resolve(directory)
            clearScreenshots(output)
            return UxReport(
                output,
                System.getProperty("hunk.uiDisplayBackend", "native"),
            )
        }

        private fun configuredOutput(): Path =
            Path.of(System.getProperty("hunk.uxReportDir")).toAbsolutePath().normalize()

        private fun clearScreenshots(output: Path) {
            if (Files.isDirectory(output)) {
                Files.list(output).use { entries ->
                    entries.filter { it.fileName.toString().matches(Regex("\\d{2}-.*\\.png")) }
                        .forEach(Files::deleteIfExists)
                }
            }
        }

        private fun escape(value: String): String = buildString(value.length) {
            value.forEach { character ->
                append(
                    when (character) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> character
                    },
                )
            }
        }
    }
}
