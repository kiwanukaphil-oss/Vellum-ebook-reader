package app.vellum.reader.core.session

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.vellum.reader.core.data.ReadingSessionEntity
import java.util.UUID

/** Counts only time while the reader destination is actually visible/started. */
class ActiveReadingSession(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val createdAt = wallClock()
    private var activeSince: Long? = null
    private var accumulatedMs = 0L

    @Synchronized
    fun setActive(active: Boolean) {
        val now = monotonicClock()
        if (active && activeSince == null) {
            activeSince = now
        } else if (!active) {
            activeSince?.let { accumulatedMs += (now - it).coerceAtLeast(0L) }
            activeSince = null
        }
    }

    @Synchronized
    fun elapsedMs(): Long = accumulatedMs + activeSince?.let {
        (monotonicClock() - it).coerceAtLeast(0L)
    }.orZero()

    private fun Long?.orZero(): Long = this ?: 0L

    @Synchronized
    fun finish(bookUuid: String, pagesTurned: Int): ReadingSessionEntity? {
        setActive(false)
        if (accumulatedMs < 30_000L) return null
        return ReadingSessionEntity(
            uuid = UUID.randomUUID().toString(),
            bookUuid = bookUuid,
            startedAt = createdAt,
            endedAt = wallClock(),
            msRead = accumulatedMs,
            pagesTurned = pagesTurned,
        )
    }
}

@Composable
fun ActiveReadingEffect(onActiveChanged: (Boolean) -> Unit) {
    val owner = LocalContext.current as LifecycleOwner
    DisposableEffect(owner, onActiveChanged) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onActiveChanged(true)
                Lifecycle.Event.ON_STOP -> onActiveChanged(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onActiveChanged(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            onActiveChanged(false)
        }
    }
}
