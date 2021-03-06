package me.syari.discord.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.syari.discord.KtDiscord.LOGGER
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.system.measureTimeMillis

internal object Timer {
    /**
     * Creates a timer that executes the specified [action] periodically.
     */
    fun CoroutineScope.timer(
        interval: Long, context: CoroutineContext = EmptyCoroutineContext, action: suspend () -> Unit
    ): Job {
        return launch(context) {

            while (isActive) {
                val time = measureTimeMillis {
                    try {
                        action()
                    } catch (ex: Exception) {
                        LOGGER.error("Coroutine Timer", ex)
                    }
                }

                delay(0L.coerceAtLeast(interval - time))
            }
        }
    }
}