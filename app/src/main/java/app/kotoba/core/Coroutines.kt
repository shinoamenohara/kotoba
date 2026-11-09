package app.kotoba.core

import kotlin.coroutines.cancellation.CancellationException

/** Like [runCatching] but lets coroutine cancellation propagate instead of wrapping it in a failure. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
