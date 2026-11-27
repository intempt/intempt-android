package com.intempt.core.flags

/**
 * F15: the last-known value is served when a read fails, for up to 24 h. While the stream is
 * connected an answer younger than [FRESH_TTL_MS] is served without asking, because the stream
 * drops a changed key at once; without a stream the server is asked on every call. At most
 * [CACHE_MAX] entries, oldest dropped.
 */
internal const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
internal const val FRESH_TTL_MS = 60L * 1000
internal const val CACHE_MAX = 10_000

/**
 * Node's cache is single-threaded; here reads arrive from any coroutine and the stream's thread,
 * so every access holds the one lock. Insertion order is age order: a rewrite removes then puts.
 */
internal class FlagCache(
    private val clock: () -> Long,
    private val max: Int = CACHE_MAX,
) {
    private class Entry(
        val key: String,
        val flag: RawFlag,
        val at: Long,
        val started: Long,
        val trusted: Boolean,
    )

    /** A read in flight. A drop that lands mid-request marks it, so its answer is not trusted as fresh. */
    class PendingRead(val key: String) {
        @Volatile
        var stale = false
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val pending = HashSet<PendingRead>()

    val size: Int get() = synchronized(lock) { entries.size }

    /** Reads in flight. A read that never ends would hold its key here for the life of the client. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /** An answer older than the current connection, or one the stream could not vouch for, is not fresh. */
    fun fresh(
        id: String,
        connected: Boolean,
        connectedAt: Long,
    ): RawFlag? =
        synchronized(lock) {
            val entry = entries[id]
            when {
                !connected || entry == null || !entry.trusted -> null
                entry.started < connectedAt || clock() - entry.at > FRESH_TTL_MS -> null
                else -> entry.flag
            }
        }

    fun recall(id: String): RawFlag? =
        synchronized(lock) {
            val entry = entries[id] ?: return null
            if (clock() - entry.at > CACHE_TTL_MS) {
                entries.remove(id)
                return null
            }
            entry.flag
        }

    fun remember(
        id: String,
        key: String,
        flag: RawFlag,
        started: Long,
        trusted: Boolean,
    ) = synchronized(lock) {
        entries.remove(id)
        entries[id] = Entry(key, flag, clock(), started, trusted)
        if (entries.size > max) entries.remove(entries.keys.first())
    }

    fun forget(id: String) {
        synchronized(lock) { entries.remove(id) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** [keys] empty drops every entry: a malformed event cannot be trusted to name its keys. */
    fun drop(keys: List<String>) =
        synchronized(lock) {
            for (read in pending) {
                if (keys.isEmpty() || read.key in keys) read.stale = true
            }
            if (keys.isEmpty()) {
                entries.clear()
            } else {
                val iterator = entries.values.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().key in keys) iterator.remove()
                }
            }
        }

    fun begin(key: String): PendingRead = PendingRead(key).also { synchronized(lock) { pending.add(it) } }

    fun end(read: PendingRead) {
        synchronized(lock) { pending.remove(read) }
    }

    fun markPendingStale() =
        synchronized(lock) {
            for (read in pending) read.stale = true
        }
}
