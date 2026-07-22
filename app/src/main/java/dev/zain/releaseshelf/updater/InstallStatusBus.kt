package dev.zain.releaseshelf.updater

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process events for PackageInstaller sessions so the UI can show progress
 * even when the system install dialog is suppressed or the app is backgrounded.
 */
object InstallStatusBus {
    sealed class Event {
        data class Started(
            val repositoryFullName: String,
            val displayName: String,
        ) : Event()

        data class Progress(
            val repositoryFullName: String,
            val progress: Float,
        ) : Event()

        data class Finished(
            val repositoryFullName: String?,
            val packageName: String?,
            val displayName: String,
            val success: Boolean,
            val message: String?,
        ) : Event()
    }

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events = mutableEvents.asSharedFlow()

    fun tryEmit(event: Event): Boolean = mutableEvents.tryEmit(event)
}
