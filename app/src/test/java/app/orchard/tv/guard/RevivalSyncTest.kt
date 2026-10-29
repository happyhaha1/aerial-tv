package app.orchard.tv.guard

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@LooperMode(LooperMode.Mode.PAUSED)
class RevivalSyncTest {
    @Test
    fun enabledButInactiveGuardIsReboundWithoutRemovingOtherServices() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        shadowOf(context.getSystemService(Context.USER_SERVICE) as UserManager).setUserUnlocked(true)
        val other = "example.voice/example.voice.MonitorService"
        val guard = "${context.packageName}/${HomeGuardService::class.java.name}"
        val resolver = context.contentResolver
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "$other:$guard")
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        val adapterType = Class.forName("app.orchard.tv.guard.RevivalSyncService\$Adapter")
        val adapter = adapterType.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(context) as AbstractThreadedSyncAdapter

        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("settings", object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
            override fun getType(uri: android.net.Uri): String? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        val provider = checkNotNull(resolver.acquireContentProviderClient(Settings.Secure.CONTENT_URI))
        try {
            adapter.onPerformSync(Account("Aerial", context.packageName), Bundle(), "settings", provider, SyncResult())
        } finally {
            provider.release()
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(other, Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertEquals("$other:$guard", Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(1, Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED))
        assertEquals("", context.getSharedPreferences("system", Context.MODE_PRIVATE).getString("revivals", ""))
    }
}
