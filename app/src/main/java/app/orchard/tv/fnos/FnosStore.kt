package app.orchard.tv.fnos

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the NAS film library's address and account are kept, and the token of the current session.
 *
 * The password is stored in clear, beside the other Aerial settings: the NAS is on the home network,
 * and the alternative (typing a password on a TV remote at every launch) is worse. The token is held
 * in memory only; [login] fetches a new one whenever the app starts without it.
 */
object FnosStore {
    private const val PREFS = "fnos"
    private const val KEY_HOST = "host"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"

    private lateinit var prefs: android.content.SharedPreferences

    /** Changes whenever credentials or the active session are invalidated. */
    @Volatile
    var revision: Long = 0L
        private set

    /** Whether an address and account have been saved, and the password is not empty. */
    var configured: Boolean = false
        private set

    /** The signed-in session token, or null before [login] and after [forget]. */
    var token: String? = null
        private set

    /** The host as saved, normalised to `http://host:port` when the user saved one. */
    var host: String = ""
        private set

    var user: String = ""
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        host = prefs.getString(KEY_HOST, null).orEmpty()
        user = prefs.getString(KEY_USER, null).orEmpty()
        configured = host.isNotEmpty() && user.isNotEmpty() && password().isNotEmpty()
    }

    /** Saves host, account and password, and drops any token from a previous account. */
    @Synchronized
    fun save(host: String, user: String, password: String) {
        val h = host.trim().let { if (it.isEmpty()) it else FnosClient.normalize(it) }
        prefs.edit {
            putString(KEY_HOST, h)
            putString(KEY_USER, user.trim())
            putString(KEY_PASSWORD, password)
        }
        this.host = h
        this.user = user.trim()
        token = null
        revision++
        configured = h.isNotEmpty() && this.user.isNotEmpty() && password.isNotEmpty()
    }

    /** Forgets the account and the token. */
    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        host = ""
        user = ""
        token = null
        revision++
        configured = false
    }

    private fun password(): String =
        if (::prefs.isInitialized) prefs.getString(KEY_PASSWORD, null).orEmpty() else ""

    /** A blank field may reuse a password only for the same server and account. */
    @Synchronized
    fun savedPassword(host: String, user: String): String =
        if (host.isNotBlank() && FnosClient.normalize(host) == this.host && user.trim() == this.user) password() else ""

    /**
     * Signs in with the saved account and keeps the token, which is a no-op while one is held. On
     * success [host] holds the normalised address.
     */
    suspend fun login(): Result<FnosClient.Session> = withContext(Dispatchers.IO) {
        val saved = synchronized(this@FnosStore) {
            if (!configured) return@withContext Result.failure(FnosException(-1, "not configured"))
            token?.let { return@withContext Result.success(FnosClient.Session(host, it, user)) }
            LoginState(revision, host, user, password())
        }
        val result = FnosClient(saved.host, null).login(saved.host, saved.user, saved.password)
        synchronized(this@FnosStore) {
            if (revision != saved.revision) {
                Result.failure(FnosException(-1, "account changed during login"))
            } else {
                result.onSuccess { token = it.token }
            }
        }
    }

    private class LoginState(val revision: Long, val host: String, val user: String, val password: String)

    /** The client for the current session; null until [login] has succeeded. */
    fun client(): FnosClient? = token?.let { FnosClient(host, it, user) }

    /** Drops the token, so the next [login] signs in afresh. */
    @Synchronized
    fun forget() {
        token = null
        revision++
    }
}
