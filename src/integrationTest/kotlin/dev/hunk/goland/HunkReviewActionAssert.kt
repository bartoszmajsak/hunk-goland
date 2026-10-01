package dev.hunk.goland

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.assertj.core.api.AbstractAssert
import java.nio.file.Path

/** Fluent structural assertions for native session actions and the test PTY's saved-note evidence. */
class HunkReviewActionAssert private constructor(actual: String) :
    AbstractAssert<HunkReviewActionAssert, String>(actual, HunkReviewActionAssert::class.java) {
    private val root = parse(actual)

    fun targetsRepository(repository: Path) = apply {
        hasString(repository.toAbsolutePath().toString(), "selector", "repoRoot")
    }

    fun targetsSession(sessionId: String) = apply {
        hasString(sessionId, "selector", "sessionId")
    }

    fun isFromIde(author: String) = apply {
        hasString(author, "author")
    }

    fun targetsFile(filePath: String) = apply {
        hasString(filePath, "filePath")
    }

    fun addsComment(line: Int, body: String) = apply {
        hasString("pty-user-note", "action")
        hasString("new", "side")
        hasInt(line, "line")
        hasString(body, "body")
    }

    fun removesComment(noteId: String) = apply {
        hasString("comment-rm", "action")
        hasString(noteId, "commentId")
    }

    fun addsRangeComment(firstLine: Int, lastLine: Int, body: String) = apply {
        addsComment(firstLine, body)
        hasInt(lastLine, "endLine")
    }

    private fun hasString(expected: String, vararg path: String) {
        val value = element(*path)?.takeIf(JsonElement::isJsonPrimitive)?.asString
        if (value != expected) {
            failWithMessage(
                "Expected <%s> to be <%s>, but was <%s>. Full request: <%s>",
                path.joinToString("."),
                expected,
                value,
                actual,
            )
        }
    }

    private fun hasInt(expected: Int, vararg path: String) {
        val value = element(*path)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isNumber }
            ?.asInt
        if (value != expected) {
            failWithMessage(
                "Expected <%s> to be <%s>, but was <%s>. Full request: <%s>",
                path.joinToString("."),
                expected,
                value,
                actual,
            )
        }
    }

    private fun element(vararg path: String): JsonElement? {
        var current: JsonElement = root
        path.forEach { segment ->
            if (!current.isJsonObject) {
                return null
            }
            current = current.asJsonObject.get(segment) ?: return null
        }
        return current
    }

    companion object {
        fun assertThat(actual: String) = HunkReviewActionAssert(actual)

        private fun parse(actual: String): JsonObject = try {
            JsonParser.parseString(actual).asJsonObject
        } catch (error: RuntimeException) {
            throw AssertionError("Expected JSON Hunk action evidence, but got: $actual", error)
        }
    }
}
