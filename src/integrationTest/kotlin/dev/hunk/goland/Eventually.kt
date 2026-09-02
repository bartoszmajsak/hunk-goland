package dev.hunk.goland

import org.awaitility.Awaitility.await
import java.util.concurrent.Callable
import java.util.function.Predicate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

internal fun awaitCondition(
    label: String,
    timeout: Duration = 20.seconds,
    condition: () -> Boolean,
) {
    await()
        .alias(label)
        .atMost(timeout.toJavaDuration())
        .pollInterval(200.milliseconds.toJavaDuration())
        .until(condition)
}

internal fun <T : Any> awaitValue(
    label: String,
    timeout: Duration = 30.seconds,
    value: () -> T?,
): T {
    val result = await()
        .alias(label)
        .atMost(timeout.toJavaDuration())
        .pollInterval(200.milliseconds.toJavaDuration())
        .until(Callable { value() }, Predicate<T?> { it != null })
    return requireNotNull(result)
}
