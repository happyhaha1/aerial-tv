package app.orchard.tv.fnos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shelf's state, shared by Home and the player: what to continue watching on the fnos server,
 * and what it is doing right now.
 *
 * A shelf is read on Home's first start, when Home comes back to the front, and after a film ends,
 * never on a timer: the idle desktop must not wake the network (see ARCHITECTURE, performance).
 */
object FnosShelf {
    private const val TAG = "AerialFnos"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last list read, in the server's order. Kept across restarts only in memory. */
    var items by mutableStateOf<List<FnosPlayItem>>(emptyList()); private set

    /** The list is being read right now. */
    var loading by mutableStateOf(false); private set

    /**
     * Why the last read produced nothing: no server set up, a sign-in that no longer holds, the
     * server away, or an empty list. Null while a list is showing.
     */
    var problem by mutableStateOf<ShelfProblem?>(null); private set

    /** Set by whoever starts a title, so the shelf knows to read the new position when it comes back. */
    var playedAt by mutableStateOf(0L); private set

    enum class ShelfProblem { NotSetUp, SignedOut, Unreachable, Empty }

    /** Under a minute left counts as watched: one tap would end the film. */
    private const val FINISHED_SEC = 60L

    private var readAt = 0L
    private var inFlight = false
    private var generation = 0L

    /** The signed-in server of the list on show, for covers and for the player. */
    var sessionFor: FnosClient.Session? = null
        private set

    /**
     * The shelf's own place: which title Home is showing full screen. Kept here, not in Home, so it
     * survives Home being recreated and so the player comes back to the same title.
     */
    var heroIndex by mutableStateOf(0)

    /**
     * What Home can offer to resume: the list without the films that are all but finished. tvOS
     * leaves those out of its shelf — tapping one would end the film in seconds — and the fnos
     * list keeps them at the top for a while after they end.
     */
    val resumable: List<FnosPlayItem>
        get() = items.filter { it.durationSec <= 0 || it.durationSec - it.tsSec > FINISHED_SEC }

    /** The title Home shows, or null while nothing is left to resume. */
    val hero: FnosPlayItem?
        get() = resumable.let { list -> list.getOrNull(heroIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))) }

    /** Steps through the titles Home offers, as left and right on the hero do; wraps at both ends. */
    fun step(delta: Int) {
        val list = resumable
        if (list.isEmpty()) return
        heroIndex = (heroIndex + delta).mod(list.size)
    }

    /** Reads the list unless [maxAgeMs] has not passed since the last read; [force] reads anyway. */
    fun refresh(force: Boolean = false, maxAgeMs: Long = 0L) {
        DiagLog.i("$TAG shelf refresh force=$force configured=${FnosStore.configured} inFlight=$inFlight items=${items.size}")
        if (!FnosStore.configured) {
            items = emptyList()
            problem = ShelfProblem.NotSetUp
            return
        }
        val stale = System.currentTimeMillis() - readAt > maxAgeMs
        if (inFlight || (!force && !stale)) return
        inFlight = true
        loading = true
        val requestGeneration = generation
        val accountRevision = FnosStore.revision
        scope.launch {
            val read = runCatching {
                val session = FnosStore.login().getOrThrow()
                session to FnosClient(session.host, session.token, session.user).playList()
            }
            withContext(Dispatchers.Main) {
                if (requestGeneration != generation) return@withContext
                if (accountRevision != FnosStore.revision) {
                    loading = false
                    inFlight = false
                    return@withContext
                }
                read
                    .onSuccess { (session, list) ->
                        sessionFor = session
                        items = list
                        heroIndex = heroIndex.coerceIn(0, (resumable.size - 1).coerceAtLeast(0))
                        problem = if (list.isEmpty()) ShelfProblem.Empty else null
                        readAt = System.currentTimeMillis()
                    }
                    .onFailure { e ->
                        DiagLog.i("$TAG shelf read failed: $e")
                        problem = when (e) {
                            is FnosException -> if (e.code == 401 || e.code == 403) ShelfProblem.SignedOut else ShelfProblem.Unreachable
                            else -> ShelfProblem.Unreachable
                        }
                    }
                if (problem == ShelfProblem.SignedOut) FnosStore.forget()
                loading = false
                inFlight = false
            }
        }
    }

    /** Called by the player when it starts a title, so Home reads the shelf again on its return. */
    fun started() {
        readAt = 0L
        playedAt = System.currentTimeMillis()
    }

    /** The server changed or was signed out: next read starts from nothing. */
    fun reset() {
        generation++
        inFlight = false
        loading = false
        heroIndex = 0
        items = emptyList()
        problem = null
        readAt = 0L
        sessionFor = null
    }
}
