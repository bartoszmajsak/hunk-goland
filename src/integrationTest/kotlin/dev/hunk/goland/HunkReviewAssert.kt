package dev.hunk.goland

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.assertj.core.api.AbstractAssert

/** Structural assertions over the review document returned by the real Hunk daemon. */
internal class HunkReviewAssert private constructor(actual: String) :
    AbstractAssert<HunkReviewAssert, String>(actual, HunkReviewAssert::class.java) {
    private val notes = parseNotes(actual)

    fun hasReviewerComment(body: String, author: String, line: Int) = apply {
        hasNote(body = body, source = "user", author = author, side = "new", line = line)
    }

    fun hasAgentResponse(body: String, line: Int) = apply {
        hasNote(body = body, source = "agent", side = "new", line = line)
    }

    fun hasAgentReply(body: String, parentId: String, line: Int) = apply {
        hasAgentResponse(body, line)
        if (notes.none { it.body() == body && it.string("parentId") == parentId }) {
            failWithMessage("Expected native agent reply to parent <%s> for <%s>", parentId, body)
        }
    }

    fun hasReviewerRange(body: String, firstLine: Int, lastLine: Int) = apply {
        val matching = notes.filter { it.body() == body && it.string("source") == "user" }
        if (matching.none { note ->
                note.getAsJsonArray("newRange")?.let { range ->
                    range.size() == 2 && range[0].asInt == firstLine && range[1].asInt == lastLine
                } == true
            }) {
            failWithMessage("Expected a native reviewer range <%s-%s> for <%s>; matching notes <%s>",
                firstLine, lastLine, body, matching)
        }
    }

    fun doesNotHaveNote(body: String) = apply {
        if (notes.any { it.body() == body }) {
            failWithMessage(
                "Expected review not to contain note <%s>, but it did. Full review: <%s>",
                body,
                actual,
            )
        }
    }

    private fun hasNote(
        body: String,
        source: String,
        author: String? = null,
        side: String,
        line: Int,
    ) {
        val matching = notes.filter { it.body() == body }
        val hasMatchingNote = matching.any { note ->
            note.string("source") == source &&
                (author == null || note.string("author") == author) &&
                note.targets(side, line)
        }
        if (!hasMatchingNote) {
            failWithMessage(
                "Expected review note body <%s> with source <%s>, author <%s>, and target <%s:%s>. " +
                    "Matching bodies were <%s>. Full review: <%s>",
                body,
                source,
                author,
                side,
                line,
                matching,
                actual,
            )
        }
    }

    companion object {
        fun assertThat(actual: String) = HunkReviewAssert(actual)

        fun containsNote(actual: String, body: String): Boolean =
            parseNotes(actual).any { it.body() == body }

        fun noteId(actual: String, body: String): String =
            parseNotes(actual).single { it.body() == body }.string("noteId")
                ?: throw AssertionError("Native note has no identity")

        private fun parseNotes(actual: String): List<JsonObject> = try {
            JsonParser.parseString(actual)
                .objectValue()
                ?.objectValue("review")
                ?.get("reviewNotes")
                ?.takeIf(JsonElement::isJsonArray)
                ?.asJsonArray
                ?.mapNotNull(JsonElement::objectValue)
                ?: throw AssertionError("Hunk review response has no review.reviewNotes array: $actual")
        } catch (error: RuntimeException) {
            throw AssertionError("Expected a Hunk review JSON response, but got: $actual", error)
        }
    }
}

private fun JsonElement.objectValue(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.objectValue(name: String): JsonObject? = get(name)?.objectValue()

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

private fun JsonObject.body(): String? = string("body") ?: string("summary")

private fun JsonObject.targets(side: String, line: Int): Boolean {
    if (string("side") == side && int("line") == line) {
        return true
    }
    val range = get("${side}Range")
        ?.takeIf { it.isJsonArray && it.asJsonArray.size() == 2 }
        ?.asJsonArray
        ?: return false
    val start = range[0].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    val end = range[1].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    return start != null && end != null && line in start..end
}
