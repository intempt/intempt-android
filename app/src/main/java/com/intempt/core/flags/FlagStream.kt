package com.intempt.core.flags

internal fun interface StreamHandle {
    fun close()
}

internal fun interface Cancellable {
    fun cancel()
}

internal class StreamHandlers(
    val onOpen: () -> Unit,
    val onMessage: (event: String, data: String) -> Unit,
    val onClose: () -> Unit,
)

private const val BACKOFF_START_MS = 1_000L
private const val BACKOFF_MAX_MS = 60_000L
private const val FLAGS_CHANGED = "flags_changed"

/**
 * The one SSE connection to `/flags/stream` (F15, contract C2): reconnects with a backoff of 1 s
 * doubling to 60 s, reset by a successful open.
 *
 * Unlike Node's, [stop] is not final: a phone app is backgrounded and foregrounded, and an open
 * socket with a 60 s retry loop behind a stopped app costs battery for answers nobody reads. The
 * next read after [stop] reconnects; [close] is final.
 *
 * Handlers carry the generation they were opened under, so a late callback from a connection that
 * was stopped or replaced is ignored instead of flipping the state of the live one.
 */
internal class FlagStream(
    private val open: (StreamHandlers) -> StreamHandle,
    private val schedule: (Long, () -> Unit) -> Cancellable,
    private val onChange: (List<String>) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var handle: StreamHandle? = null
    private var timer: Cancellable? = null
    private var generation = 0
    private var delay = BACKOFF_START_MS
    private var closed = false

    @Volatile
    var connected = false
        private set

    fun start() =
        synchronized(lock) {
            if (!closed && handle == null && timer == null) connect()
        }

    fun stop() =
        synchronized(lock) {
            generation++
            timer?.cancel()
            timer = null
            handle?.close()
            handle = null
            setConnected(false)
        }

    fun close() =
        synchronized(lock) {
            closed = true
            stop()
        }

    private fun connect() {
        val mine = ++generation
        handle =
            open(
                StreamHandlers(
                    onOpen = {
                        synchronized(lock) {
                            if (mine == generation) {
                                delay = BACKOFF_START_MS
                                setConnected(true)
                            }
                        }
                    },
                    onMessage = { event, data ->
                        val live = synchronized(lock) { mine == generation }
                        if (live && event == FLAGS_CHANGED) onChange(keysOfFlagsChanged(data))
                    },
                    onClose = {
                        synchronized(lock) {
                            if (mine == generation) {
                                handle = null
                                setConnected(false)
                                schedule()
                            }
                        }
                    },
                ),
            )
    }

    private fun schedule() {
        if (closed) return
        val mine = generation
        timer =
            schedule(delay) {
                synchronized(lock) {
                    if (mine == generation && timer != null) {
                        timer = null
                        connect()
                    }
                }
            }
        delay = minOf(delay * 2, BACKOFF_MAX_MS)
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        onState(value)
    }
}
