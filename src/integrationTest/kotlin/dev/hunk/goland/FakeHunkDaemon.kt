package dev.hunk.goland

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.hunk.goland.testing.HunkBrokerTestAuthentication
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A typed in-process adapter for the Hunk session seam exercised by the GoLand UI test. */
internal class FakeHunkDaemon private constructor(
    private val reviewerName: String,
    val runtimeDirectory: Path,
) : AutoCloseable {
    private val authentication = HunkBrokerTestAuthentication(runtimeDirectory)
    private val lock = Any()
    private val state = ReviewState()
    private val requests = LinkedBlockingQueue<String>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fake-hunk-daemon").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@FakeHunkDaemon.executor
        authentication.install(this)
        createContext("/session-api/capabilities", ::handleCapabilities)
        createContext("/session-api", ::handleSessionRequest)
        start()
    }

    val port: Int
        get() = server.address.port

    val reviewReadCount: Int
        get() = synchronized(lock) { state.reviewReads }

    /** Register an independently owned TUI before GoLand starts its managed review. */
    fun connectExternalReview(repoRoot: Path) = synchronized(lock) {
        state.externalRepoRoot = repoRoot.toAbsolutePath().normalize().toString()
        state.externalReviewAvailable = true
    }

    /** Publish an update as if another attached client had added an agent response. */
    fun publishExternalAgentResponse(): Int = synchronized(lock) {
        check(!state.externalAgentResponseAdded) { "External agent response was already published" }
        state.externalAgentResponseAdded = true
        state.revision += 1
        state.reviewReads
    }

    fun disconnectReview() = synchronized(lock) {
        state.reviewAvailable = false
        state.externalReviewAvailable = false
    }

    fun takeReviewAction(context: String): String = awaitValue(
        label = "review action submitted from $context",
    ) {
        requests.poll()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
            "Fake Hunk daemon executor did not terminate"
        }
    }

    private fun handleCapabilities(exchange: HttpExchange) {
        if (!authentication.authorize(exchange, byteArrayOf(), false)) return
        respond(
            exchange,
            JsonObject().apply {
                addProperty("version", 1)
                addProperty("daemonVersion", 16)
                add("actions", JsonArray().apply {
                    add("review")
                    add("navigate")
                    add("comment-rm")
                })
            },
        )
    }

    private fun handleSessionRequest(exchange: HttpExchange) {
        val bytes = exchange.requestBody.use { it.readBytes() }
        val raw = String(bytes, StandardCharsets.UTF_8)
        val request = parseObject(raw)
        if (request == null) {
            respondError(exchange, "Request body must be a JSON object")
            return
        }

        val action = request.string("action")
        // Test-only endpoints are the fixture's native-composer relay, never stock actions.
        if (!action.orEmpty().startsWith("test-") && !authentication.authorize(exchange, bytes, action != "list")) return
        when (action) {
            "list" -> listSessions(exchange)
            "review" -> readReview(exchange, request)
            "navigate" -> navigate(exchange, request)
            "comment-rm" -> removeComment(exchange, request, raw)
            "test-register" -> registerManagedSession(exchange, request)
            "test-add-user" -> addManagedUserNote(exchange, request)
            "test-selection" -> respond(exchange, synchronized(lock) {
                JsonObject().apply {
                    addProperty("filePath", state.navigation?.filePath)
                    addProperty("line", state.navigation?.line)
                    addProperty("endLine", state.navigation?.line)
                }
            })
            else -> respondError(exchange, "Unsupported UI fixture action")
        }
    }

    private fun listSessions(exchange: HttpExchange) {
        val snapshot = synchronized(lock) {
            SessionSnapshot(
                state.reviewAvailable,
                state.repoRoot,
                state.pid,
                state.externalReviewAvailable,
                state.externalRepoRoot,
            )
        }
        respond(exchange, JsonObject().apply {
            add("sessions", JsonArray().apply {
                if (snapshot.managedAvailable && snapshot.managedRepoRoot != null && snapshot.managedPid != null) {
                    add(JsonObject().apply {
                        addProperty("sessionId", "ui-session")
                        addProperty("repoRoot", snapshot.managedRepoRoot)
                        addProperty("pid", snapshot.managedPid)
                    })
                }
                if (snapshot.externalAvailable && snapshot.externalRepoRoot != null) {
                    add(JsonObject().apply {
                        addProperty("sessionId", "external-session")
                        addProperty("repoRoot", snapshot.externalRepoRoot)
                        addProperty("pid", 424_242)
                    })
                }
            })
        })
    }

    private fun readReview(exchange: HttpExchange, request: JsonObject) {
        val selector = request.objectValue("selector")
        val available = synchronized(lock) {
            when (selector?.string("sessionId")) {
                "ui-session" -> state.reviewAvailable
                "external-session" -> state.externalReviewAvailable
                else -> state.reviewAvailable || state.externalReviewAvailable
            }
        }
        if (!available) {
            val repository = request.objectValue("selector")?.string("repoRoot") ?: "this worktree"
            respondError(exchange, "No active session matches repoRoot $repository")
            return
        }
        respond(exchange, reviewResponse(readSnapshot()))
    }

    private fun registerManagedSession(exchange: HttpExchange, request: JsonObject) {
        val repoRoot = request.string("repoRoot")
        val pid = request.positiveLong("pid")
        if (repoRoot == null || pid == null) {
            respondError(exchange, "Managed-session registration requires repoRoot and pid")
            return
        }
        synchronized(lock) {
            state.repoRoot = repoRoot
            state.pid = pid
            state.reviewAvailable = true
        }
        respond(exchange, JsonObject().apply { addProperty("registered", true) })
    }

    private fun navigate(exchange: HttpExchange, request: JsonObject) {
        val filePath = request.string("filePath")
        val side = request.string("side")
        val line = request.positiveInt("line")
        if (filePath == null || side == null || side !in setOf("old", "new") || line == null) {
            respondError(exchange, "Malformed navigate request")
            return
        }
        synchronized(lock) {
            state.navigation = Navigation(filePath, side, line)
        }
        respond(exchange, JsonObject().apply {
            add("result", JsonObject().apply {
                addProperty("filePath", filePath)
                addProperty("side", side)
                addProperty("line", line)
            })
        })
    }

    private fun addManagedUserNote(exchange: HttpExchange, request: JsonObject) {
        val body = request.string("body")
        val author = request.string("author") ?: reviewerName
        val action = synchronized(lock) {
            val target = state.navigation
            if (target == null || body.isNullOrBlank()) null else AddedComment(
                filePath = target.filePath,
                side = target.side,
                line = target.line,
                endLine = request.positiveInt("endLine") ?: target.line,
                body = body,
                author = author,
            )
        }
        if (action == null) {
            respondError(exchange, "No navigated reviewer target or body")
            return
        }
        synchronized(lock) {
            state.addedComments += action
            state.revision += 1
        }
        requests.add(JsonObject().apply {
            addProperty("action", "pty-user-note")
            add("selector", request.objectValue("selector") ?: JsonObject().apply {
                addProperty("repoRoot", synchronized(lock) { state.repoRoot })
            })
            addProperty("filePath", action.filePath)
            addProperty("side", action.side)
            addProperty("line", action.line)
            addProperty("endLine", action.endLine)
            addProperty("body", action.body)
            addProperty("author", action.author)
        }.toString())
        respond(exchange, JsonObject().apply { addProperty("saved", true) })
    }

    private fun removeComment(exchange: HttpExchange, request: JsonObject, raw: String) {
        val noteId = request.string("commentId")
        val removed = synchronized(lock) {
            when (noteId) {
                INITIAL_REVIEWER_NOTE_ID -> {
                    state.initialReviewerCommentDeleted = true
                    true
                }
                else -> false
            }.also { didRemove -> if (didRemove) state.revision += 1 }
        }
        if (removed) requests.add(raw)
        respond(
            exchange,
            JsonObject().apply {
                add("result", JsonObject().apply {
                    addProperty("commentId", noteId)
                    addProperty("removed", removed)
                })
            },
        )
    }

    private fun readSnapshot(): ReviewSnapshot = synchronized(lock) {
        state.reviewReads += 1
        ReviewSnapshot(
            revision = state.revision,
            externalAgentResponseAdded = state.externalAgentResponseAdded,
            initialReviewerCommentDeleted = state.initialReviewerCommentDeleted,
            addedComments = state.addedComments.toList(),
        )
    }

    private fun reviewResponse(snapshot: ReviewSnapshot): JsonObject {
        val notes = JsonArray()
        // Deliberately return this newer item before the reviewer comment. The plugin must use
        // createdAt rather than daemon array order when it renders a conversation.
        notes.add(
            note(
                id = "agent-new",
                source = "agent",
                side = "new",
                line = 4,
                body = "Agent response: the `cancellation` branch is now covered by a **focused test**.",
                author = "receiver-agent",
                createdAt = "2026-08-29T10:01:00Z",
                editable = false,
            ),
        )
        if (!snapshot.initialReviewerCommentDeleted) {
            notes.add(
                note(
                    id = INITIAL_REVIEWER_NOTE_ID,
                    source = "user",
                    side = "new",
                    line = 4,
                    body = "Keep the **cancellation semantics** explicit.",
                    author = "Ari (reviewer)",
                    createdAt = "2026-08-29T10:00:00Z",
                    editable = true,
                ),
            )
        }
        notes.add(
            note(
                id = "agent-context",
                source = "agent",
                side = "new",
                line = 4,
                body = "Agent context: this earlier observation should remain at the top of the thread.",
                author = "context-agent",
                createdAt = "2026-08-29T09:57:00Z",
                editable = false,
            ),
        )
        notes.add(
            note(
                id = "agent-added-file",
                filePath = "cmd/added.go",
                source = "agent",
                side = "new",
                line = 4,
                body = "Agent response: the new greeting is ready for review.",
                author = "receiver-agent",
                createdAt = "2026-08-29T10:01:30Z",
                editable = false,
            ),
        )
        notes.add(
            note(
                id = "review-old",
                source = "user",
                side = "old",
                line = 4,
                body = "This import was part of the review baseline.",
                author = "Ari (reviewer)",
                createdAt = "2026-08-29T09:58:00Z",
                editable = true,
            ),
        )
        notes.add(
            note(
                id = "agent-old",
                source = "agent",
                side = "old",
                line = 4,
                body = "Agent response: I retained this import while applying the review.",
                author = "receiver-agent",
                createdAt = "2026-08-29T09:59:00Z",
                editable = false,
            ),
        )
        if (snapshot.externalAgentResponseAdded) {
            notes.add(
                note(
                    id = "agent-external",
                    parentId = "agent-context",
                    source = "agent",
                    side = "new",
                    line = 4,
                    body = EXTERNAL_RESPONSE,
                    author = "external-agent",
                    createdAt = "2026-08-29T10:02:00Z",
                    editable = false,
                ),
            )
        }
        snapshot.addedComments.forEachIndexed { index, comment ->
            notes.add(
                note(
                    id = "review-added-${index + 1}",
                    filePath = comment.filePath,
                    source = "user",
                    side = comment.side,
                    line = comment.line,
                    endLine = comment.endLine,
                    body = comment.body,
                    author = comment.author,
                    createdAt = "2026-08-29T10:${(3 + index).toString().padStart(2, '0')}:00Z",
                    editable = true,
                ),
            )
        }

        val agentCount = notes.count { it.asJsonObject.string("source") == "agent" }
        return JsonObject().apply {
            add("review", JsonObject().apply {
                addProperty("sessionId", "ui-session")
                addProperty("title", "UI test review")
                addProperty("sourceLabel", "fixture")
                addProperty("inputKind", "vcs")
                add("selectedFile", null)
                add("selectedHunk", null)
                addProperty("showAgentNotes", true)
                addProperty("liveCommentCount", agentCount)
                addProperty("reviewNoteCount", notes.size())
                add("reviewPublication", JsonObject().apply {
                    addProperty("generation", GENERATION)
                    addProperty("stateRevision", snapshot.revision)
                })
                add("reviewCatalog", JsonObject().apply {
                    addProperty("generation", GENERATION)
                    add("fileKeysByRuntimeId", JsonObject().apply {
                        addProperty("runtime-main", "file:abcd")
                        addProperty("runtime-added", "file:added")
                    })
                    add("resources", JsonArray())
                })
                add("files", JsonArray().apply {
                    add(reviewFile("runtime-main", "cmd/main.go", 11))
                    add(reviewFile("runtime-added", "cmd/added.go", 5))
                })
                add("reviewNotes", notes)
            })
        }
    }

    private fun reviewFile(id: String, path: String, lineCount: Int): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("path", path)
        addProperty("additions", 1)
        addProperty("deletions", 1)
        addProperty("hunkCount", 1)
        add("hunks", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                addProperty("header", "@@ -1,$lineCount +1,$lineCount @@")
                add("oldRange", JsonArray().apply {
                    add(1)
                    add(lineCount)
                })
                add("newRange", JsonArray().apply {
                    add(1)
                    add(lineCount)
                })
            })
        })
    }

    private fun note(
        id: String,
        filePath: String = "cmd/main.go",
        source: String,
        side: String,
        line: Int,
        body: String,
        author: String,
        createdAt: String,
        editable: Boolean,
        endLine: Int = line,
        parentId: String? = null,
    ): JsonObject = JsonObject().apply {
        addProperty("noteId", id)
        addProperty("source", source)
        addProperty("filePath", filePath)
        addProperty("side", side)
        addProperty("line", line)
        addProperty("endLine", endLine)
        parentId?.let { addProperty("parentId", it) }
        addProperty("body", body)
        addProperty("author", author)
        addProperty("createdAt", createdAt)
        addProperty("editable", editable)
    }

    private fun respond(exchange: HttpExchange, body: JsonObject, status: Int = 200) {
        authentication.respond(exchange, body, status)
    }

    private fun respondError(exchange: HttpExchange, message: String) {
        respond(
            exchange,
            JsonObject().apply { addProperty("error", message) },
            status = 400,
        )
    }

    private data class ReviewState(
        var revision: Int = 1,
        var reviewReads: Int = 0,
        var reviewAvailable: Boolean = false,
        var repoRoot: String? = null,
        var pid: Long? = null,
        var externalReviewAvailable: Boolean = false,
        var externalRepoRoot: String? = null,
        var navigation: Navigation? = null,
        var externalAgentResponseAdded: Boolean = false,
        var initialReviewerCommentDeleted: Boolean = false,
        val addedComments: MutableList<AddedComment> = mutableListOf(),
    )

    private data class ReviewSnapshot(
        val revision: Int,
        val externalAgentResponseAdded: Boolean,
        val initialReviewerCommentDeleted: Boolean,
        val addedComments: List<AddedComment>,
    )

    private data class SessionSnapshot(
        val managedAvailable: Boolean,
        val managedRepoRoot: String?,
        val managedPid: Long?,
        val externalAvailable: Boolean,
        val externalRepoRoot: String?,
    )

    private data class Navigation(val filePath: String, val side: String, val line: Int)

    private data class AddedComment(
        val filePath: String,
        val side: String,
        val line: Int,
        val endLine: Int,
        val body: String,
        val author: String,
    )

    companion object {
        const val EXTERNAL_RESPONSE = "External agent response: I picked up the new review context."

        private const val GENERATION = "generation:ui-test:1"
        private const val INITIAL_REVIEWER_NOTE_ID = "user:review-new"
        fun start(reviewerName: String, runtimeDirectory: Path): FakeHunkDaemon = FakeHunkDaemon(reviewerName, runtimeDirectory)

        private fun parseObject(value: String): JsonObject? = runCatching {
            JsonParser.parseString(value).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
    }
}

private fun JsonObject.objectValue(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.positiveInt(name: String): Int? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt
        ?.takeIf { it > 0 }

private fun JsonObject.positiveLong(name: String): Long? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asLong
        ?.takeIf { it > 0 }
