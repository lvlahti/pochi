package io.github.lvlahti.pochi

/**
 * Screen/audio is never accessible until a specific viewer ID + IP is approved on Android.
 * The random session URL token is checked separately by StreamServer.
 */
class ViewerGate(private val clock: () -> Long = System::currentTimeMillis) {
    data class Request(val id: String, val ip: String, val createdMs: Long)
    enum class State { PENDING, APPROVED, DENIED, INVALID, FULL }
    data class Result(val state: State, val isNew: Boolean)
    private data class Entry(val request: Request, var state: State)
    private val entries = LinkedHashMap<String, Entry>()
    private val validId = Regex("^[0-9a-f]{32}$")

    @Synchronized fun request(id: String, ip: String): Result {
        if (!validId.matches(id) || ip.isBlank()) return Result(State.INVALID, false)
        val now = clock()
        entries.entries.removeAll {
            it.value.state != State.APPROVED && now - it.value.request.createdMs > 120_000L
        }
        val existing = entries[id]
        if (existing != null) {
            return if (existing.request.ip == ip) Result(existing.state, false)
            else Result(State.INVALID, false)
        }
        if (entries.values.count { it.state == State.PENDING } >= 8 ||
            entries.size >= 32) return Result(State.FULL, false)
        entries[id] = Entry(Request(id, ip, now), State.PENDING)
        return Result(State.PENDING, true)
    }

    @Synchronized fun authorized(id: String, ip: String): Boolean {
        val entry = entries[id] ?: return false
        return entry.state == State.APPROVED && entry.request.ip == ip
    }

    @Synchronized fun decide(id: String, allow: Boolean): Boolean {
        val entry = entries[id] ?: return false
        if (entry.state != State.PENDING) return false
        entry.state = if (allow) State.APPROVED else State.DENIED
        return true
    }

    @Synchronized fun pending(): List<Request> =
        entries.values.filter { it.state == State.PENDING }.map { it.request }

    @Synchronized fun countApproved(): Int = entries.values.count { it.state == State.APPROVED }
}
