package org.wispyr.messenger.security

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wispyr.messenger.AndroidUtilities
import org.wispyr.messenger.ApplicationLoader
import org.wispyr.messenger.LocaleController
import org.wispyr.messenger.MessageKeyData
import org.wispyr.messenger.MessagesController
import org.wispyr.messenger.PushListenerController
import org.wispyr.messenger.R
import org.wispyr.messenger.ScreenReceiver
import org.wispyr.messenger.SharedConfig
import org.wispyr.messenger.UserConfig
import org.wispyr.messenger.Utilities
import org.wispyr.tgnet.ConnectionsManager
import org.wispyr.tgnet.NativeByteBuffer
import org.wispyr.tgnet.TLRPC
import org.wispyr.ui.LaunchActivity
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Multi-process scenario. Each phase must run in its own instrumentation process:
 *   phase1Prepare -> phase2ColdLockedProcess -> phase3Cleanup
 */
class ColdLockScenarioTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun phase1Prepare() {
        assertFalse("run phase3Cleanup first", WispyrVault.isPasscodeProtected())
        onUi { ApplicationLoader.postInitApplication() }
        // A permanent auth key is created by the handshake without logging in.
        val connections = ConnectionsManager.getInstance(0)
        val deadline = System.currentTimeMillis() + 30_000
        while (connections.currentAuthKeyId == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        val authKeyId = connections.currentAuthKeyId
        assertTrue("handshake must produce an auth key (network required)", authKeyId != 0L)
        context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).edit().putLong("authKeyId", authKeyId).commit()
        Thread.sleep(1500)

        val config = UserConfig.getInstance(0)
        onUi {
            config.setCurrentUser(TLRPC.TL_user().apply { id = SELF_ID; first_name = "Self"; phone = "0000"; self = true })
            config.saveConfig(true)
        }
        Thread.sleep(1500)
        assertTrue(config.preferences.edit().commit())

        SharedConfig.pushAuthKey = ByteArray(256).also { SecureRandom().nextBytes(it) }
        SharedConfig.pushAuthKeyId = null
        SharedConfig.saveConfig()

        val latch = CountDownLatch(1)
        var ok = false
        onUi {
            SharedConfig.setPasscodeAsync(PIN, SharedConfig.PASSCODE_TYPE_PIN) { success ->
                ok = success
                SharedConfig.saveConfig()
                latch.countDown()
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        assertTrue(ok)
        assertTrue(WispyrVault.isPasscodeProtected())
    }

    @Test
    fun phase2ColdLockedProcess() {
        assertTrue("fresh process must start locked", WispyrVault.isLocked())

        onUi { ApplicationLoader.postInitApplication() }
        LocaleController.getString(R.string.AppName)
        LocaleController.getInstance().loadRemoteLanguages(0)
        onUi {
            ScreenReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
            ScreenReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        }
        MessagesController.getGlobalMainSettings().getString("language", null)
        MessagesController.getGlobalNotificationsSettings().getBoolean("EnableAll", true)

        PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, "wispyr-test-token")
        Thread.sleep(500)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        push("""{"loc_key":"MESSAGE_TEXT","loc_args":["Alice","secret body"],"custom":{"msg_id":"5","from_id":"$PEER_ID"},"user_id":"$SELF_ID"}""")
        push("""{"loc_key":"PHONE_CALL_REQUEST","loc_args":["Alice"],"custom":{"call_id":"$CALL_ID","call_ah":"77","from_id":"$PEER_ID"},"user_id":"$SELF_ID"}""")

        context.startActivity(Intent(context, LaunchActivity::class.java).setAction("voip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        context.startActivity(LockedPushNotifier.callLaunchIntent(context, CALL_ID, "Alice Smith").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1500)
        captureUnlockScreen("locked_call.png")
        // Screen-off while locked used to reach native pauseNetwork() before init and bind tgnet.dat to a
        // wrong path, so the account started over with a new auth key (401 -> logout) after unlock.
        ConnectionsManager.native_pauseNetwork(0)
        Thread.sleep(3000)

        assertEquals("account controllers must not exist while locked", emptySet<String>(), WispyrLockGate.getLockedAccountAccess())
        assertNotNull("locked incoming-call notification", manager.activeNotifications.firstOrNull { it.notification.category == android.app.Notification.CATEGORY_CALL })
        assertTrue("message body must never be shown while locked",
                manager.activeNotifications.none { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.contains("secret body") == true })

        val secret = PasscodeHasher.deriveSecret(PIN, WispyrVault.getPasscodeVerifier())
        assertNotNull(secret)
        assertTrue(WispyrVault.unlock(secret))
        val deadline = System.currentTimeMillis() + 15_000
        while (!UserConfig.getInstance(0).isClientActivated && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("session must survive a cold locked start", UserConfig.getInstance(0).isClientActivated)
        assertEquals(SELF_ID, UserConfig.getInstance(0).getClientUserId())
        val expectedAuthKey = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).getLong("authKeyId", 0)
        val authDeadline = System.currentTimeMillis() + 10_000
        while (ConnectionsManager.getInstance(0).currentAuthKeyId == 0L && System.currentTimeMillis() < authDeadline) {
            Thread.sleep(100)
        }
        assertEquals("MTProto auth key must be restored after unlock", expectedAuthKey, ConnectionsManager.getInstance(0).currentAuthKeyId)

        val main = MessagesController.getGlobalMainSettings()
        assertTrue(main.edit().putString("wispyr_probe", "ok").commit())
        assertEquals("ok", MessagesController.getGlobalMainSettings().getString("wispyr_probe", null))
        assertEquals("ok", MessagesController.getInstance(0).mainSettings.getString("wispyr_probe", null))

        ChatHistoryPipelineTest().runPipeline(9_100_000_003L)
    }

    @Test
    fun phase3Cleanup() {
        if (WispyrVault.isLocked()) {
            val secret = PasscodeHasher.deriveSecret(PIN, WispyrVault.getPasscodeVerifier())
            assertTrue(secret != null && WispyrVault.unlock(secret))
        }
        onUi { ApplicationLoader.postInitApplication() }
        WispyrVault.removePasscodeProtection()
        SharedConfig.passcodeHash = ""
        SharedConfig.appLocked = false
        SharedConfig.saveConfig()
        MessagesController.getGlobalMainSettings().edit().remove("wispyr_probe").commit()
        assertFalse(WispyrVault.isPasscodeProtected())
    }

    /** Renders the resumed unlock screen to app-private storage (FLAG_SECURE blocks system screenshots). */
    private fun captureUnlockScreen(name: String) {
        onUi {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is WispyrUnlockActivity }
            assertNotNull("locked call must show the unlock screen", activity)
            val root = activity!!.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun push(json: String) {
        val payload = encryptPush(json.toByteArray(StandardCharsets.UTF_8), SharedConfig.pushAuthKey)
        PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, payload, System.currentTimeMillis())
    }

    /** Inverse of PushListenerController's decryption (MTProto 2.0 style envelope). */
    private fun encryptPush(json: ByteArray, authKey: ByteArray): String {
        var plainLength = 4 + json.size
        val padding = 16 - plainLength % 16 + 16
        plainLength += padding
        val total = 24 + plainLength
        val buffer = NativeByteBuffer(total)
        val authKeyHash = Utilities.computeSHA1(authKey)
        buffer.writeBytes(authKeyHash, authKeyHash.size - 8, 8)
        buffer.writeBytes(ByteArray(16))
        buffer.writeInt32(json.size)
        buffer.writeBytes(json)
        buffer.writeBytes(ByteArray(padding).also { SecureRandom().nextBytes(it) })
        val messageKeyFull = Utilities.computeSHA256(authKey, 88 + 8, 32, buffer.buffer, 24, buffer.buffer.limit())
        val messageKey = messageKeyFull.copyOfRange(8, 24)
        buffer.position(8)
        buffer.writeBytes(messageKey)
        val keyData = MessageKeyData.generateMessageKeyData(authKey, messageKey, true, 2)
        Utilities.aesIgeEncryption(buffer.buffer, keyData.aesKey, keyData.aesIv, true, false, 24, total - 24)
        val out = ByteArray(total)
        buffer.position(0)
        buffer.readBytes(out, false)
        buffer.reuse()
        return Base64.encodeToString(out, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun onUi(block: () -> Unit) {
        val latch = CountDownLatch(1)
        AndroidUtilities.runOnUIThread {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
    }

    companion object {
        const val PIN = "135790"
        const val SELF_ID = 7_100_000_001L
        const val PEER_ID = 7_100_000_002L
        const val CALL_ID = 4_242_424_242L
        const val TEST_PREFS = "wispyr_scenario_test"
    }
}
