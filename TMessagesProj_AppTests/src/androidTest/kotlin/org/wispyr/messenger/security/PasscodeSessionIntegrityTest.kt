package org.wispyr.messenger.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wispyr.messenger.SharedConfig
import org.wispyr.messenger.UserConfig
import org.wispyr.messenger.voip.VoIPPreNotificationService
import org.wispyr.tgnet.TLRPC

class PasscodeSessionIntegrityTest {

    @Test
    fun uiLockKeepsCallSessionLiveAndColdLockCannotClearAccount() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WispyrVault.attach(context)
        assertFalse(WispyrVault.isLocked())

        val dataKeyField = WispyrVault::class.java.getDeclaredField("dataKey").apply { isAccessible = true }
        val userField = UserConfig::class.java.getDeclaredField("currentUser").apply { isAccessible = true }
        val config = UserConfig.getInstance(0)
        val oldDataKey = dataKeyField.get(null)
        val oldUser = userField.get(config)
        val oldClientUserId = config.clientUserId
        val oldAppLocked = SharedConfig.appLocked
        val user = TLRPC.TL_user().apply { id = 8_958_503_251L }

        try {
            SharedConfig.appLocked = true
            assertFalse("UI passcode lock must not stop the authenticated call transport", WispyrVault.isLocked())

            userField.set(config, user)
            config.clientUserId = user.id
            dataKeyField.set(null, null)
            assertTrue(WispyrVault.isLocked())

            config.clearConfig()
            assertSame("A cold locked process must never erase the saved login session", user, config.currentUser)
        } finally {
            dataKeyField.set(null, oldDataKey)
            userField.set(config, oldUser)
            config.clientUserId = oldClientUserId
            SharedConfig.appLocked = oldAppLocked
        }
    }

    @Test
    fun incomingCallAnswerWaitsForPasscode() {
        val oldWaiting = SharedConfig.isWaitingForPasscodeEnter
        val oldHash = SharedConfig.passcodeHash
        val oldLocked = SharedConfig.appLocked
        try {
            SharedConfig.isWaitingForPasscodeEnter = true
            assertTrue(VoIPPreNotificationService.shouldDeferAnswerUntilPasscode())
            assertTrue(VoIPPreNotificationService.shouldDeferAnswerUntilPasscode())
            assertTrue(VoIPPreNotificationService.deferAnswerUntilPasscode(null))

            SharedConfig.isWaitingForPasscodeEnter = false
            SharedConfig.passcodeHash = "test-verifier"
            SharedConfig.appLocked = true
            assertTrue(VoIPPreNotificationService.shouldDeferAnswerUntilPasscode())
        } finally {
            SharedConfig.isWaitingForPasscodeEnter = oldWaiting
            SharedConfig.passcodeHash = oldHash
            SharedConfig.appLocked = oldLocked
        }
    }

    @Test
    fun lockedCallOpensThePasscodeScreenForThatCall() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val callId = 123_456_789L
        val intent = LockedPushNotifier.callLaunchIntent(context, callId, "Alice")

        assertEquals(WispyrUnlockActivity::class.java.name, intent.component?.className)
        assertEquals(callId, intent.getLongExtra(WispyrUnlockActivity.EXTRA_CALL_ID, 0))
        assertEquals("Alice", intent.getStringExtra(WispyrUnlockActivity.EXTRA_CALLER_NAME))
    }

    @Test
    fun clearingWithoutCallIdKeepsThePendingChoice() {
        LockedCallBridge.request(21L, LockedCallBridge.ACTION_DECLINE)
        LockedCallBridge.clear(0)
        assertEquals(LockedCallBridge.ACTION_DECLINE, LockedCallBridge.consume(21L))
    }

    @Test
    fun lockedCallChoiceIsAppliedOnceToTheSameCall() {
        LockedCallBridge.request(11L, LockedCallBridge.ACTION_DECLINE)
        assertEquals(LockedCallBridge.ACTION_NONE, LockedCallBridge.consume(12L))
        assertEquals(LockedCallBridge.ACTION_DECLINE, LockedCallBridge.consume(11L))
        assertEquals(LockedCallBridge.ACTION_NONE, LockedCallBridge.consume(11L))
    }
}
