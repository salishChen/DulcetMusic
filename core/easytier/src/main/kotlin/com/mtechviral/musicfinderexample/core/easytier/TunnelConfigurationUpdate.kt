package com.mtechviral.musicfinderexample.core.easytier

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep a previously enabled tunnel usable even when saving is cancelled or fails. */
internal suspend fun <T> updateTunnelConfiguration(
    enabled: Boolean,
    changed: Boolean,
    stop: suspend () -> Unit,
    start: suspend () -> Unit,
    update: suspend () -> T,
): T {
    if (!enabled || !changed) return update()
    try {
        stop()
        return update()
    } finally {
        withContext(NonCancellable) { start() }
    }
}
