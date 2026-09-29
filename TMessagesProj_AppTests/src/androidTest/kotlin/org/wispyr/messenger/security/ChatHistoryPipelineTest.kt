package org.wispyr.messenger.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wispyr.messenger.AndroidUtilities
import org.wispyr.messenger.ApplicationLoader
import org.wispyr.messenger.MessageObject
import org.wispyr.messenger.MessagesController
import org.wispyr.messenger.MessagesStorage
import org.wispyr.messenger.NotificationCenter
import org.wispyr.messenger.SharedConfig
import org.wispyr.messenger.UserConfig
import org.wispyr.messenger.Utilities
import org.wispyr.tgnet.ConnectionsManager
import org.wispyr.tgnet.TLRPC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives a synthetic server history page through the production pipeline
 * (processLoadedMessages -> storage -> UI notification), then reloads the chat from the local database.
 * The chat screen must see every message both times, with and without the passcode vault.
 */
class ChatHistoryPipelineTest {

    private val account = 0
    private val selfId = 7_000_001L

    @Test
    fun serverPageAndCacheReloadWithoutPasscode() {
        runPipeline(9_100_000_001L)
    }

    @Test
    fun serverPageAndCacheReloadWithPasscodeVault() {
        val secret = ByteArray(32) { it.toByte() }
        WispyrVault.protectWithPasscode(secret, "test-verifier", SharedConfig.PASSCODE_TYPE_PIN)
        try {
            assertTrue(WispyrVault.isPasscodeProtected())
            runPipeline(9_100_000_002L)
        } finally {
            WispyrVault.removePasscodeProtection()
        }
    }

    fun runPipeline(peerId: Long) {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        onUi { ApplicationLoader.postInitApplication() }
        val config = UserConfig.getInstance(account)
        if (config.currentUser == null) {
            config.setCurrentUser(TLRPC.TL_user().apply { id = selfId; first_name = "Self"; self = true })
        }
        val count = 60
        val now = ConnectionsManager.getInstance(account).currentTime.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000).toInt()
        val page = TLRPC.TL_messages_messages()
        page.users.add(TLRPC.TL_user().apply { id = peerId; first_name = "Peer"; access_hash = 1 })
        for (i in count downTo 1) {
            page.messages.add(message(i, peerId, now - (count - i) * 10, withPhoto = i % 7 == 0))
        }

        // The peer has read up to READ_OUTBOX_MAX: later outgoing messages must stay single-checked.
        MessagesController.getInstance(account).dialogs_read_outbox_max[peerId] = READ_OUTBOX_MAX
        MessagesController.getInstance(account).dialogs_read_inbox_max[peerId] = count

        val server = awaitMessagesDidLoad(guid = 91_000 + (peerId % 1000).toInt(), cache = false) { guid ->
            Utilities.stageQueue.postRunnable {
                MessagesController.getInstance(account).processLoadedMessages(page, count, peerId, 0L, 50, 0, 0, false, guid,
                        0, count, 0, 0, MessagesController.LOAD_BACKWARD, true, 0, 0L, 0, false, 0, true, false, null)
            }
        }
        assertEquals("server page must reach the chat screen", count, server.size)
        assertOutgoingReadState("server page", server)

        val cached = awaitMessagesDidLoad(guid = 92_000 + (peerId % 1000).toInt(), cache = true) { guid ->
            MessagesStorage.getInstance(account).getMessages(peerId, 0L, false, 50, 0, 0, 0, guid,
                    MessagesController.LOAD_BACKWARD, 0, 0L, 0, true, false, null)
        }
        assertEquals("reopening the chat must load history from the local database", 50, cached.size)
        assertOutgoingReadState("local database", cached)
    }

    private fun assertOutgoingReadState(source: String, messages: List<MessageObject>) {
        val outgoing = messages.filter { it.isOut }
        assertTrue(outgoing.isNotEmpty())
        for (obj in outgoing) {
            assertEquals("$source: message ${obj.id} read state", obj.id > READ_OUTBOX_MAX, obj.isUnread)
        }
    }

    private fun message(id: Int, peerId: Long, date: Int, withPhoto: Boolean): TLRPC.Message {
        val out = id % 2 == 0
        return TLRPC.TL_message().apply {
            this.id = id
            this.date = date
            this.out = out
            message = "history message $id"
            peer_id = TLRPC.TL_peerUser().apply { user_id = peerId }
            from_id = TLRPC.TL_peerUser().apply { user_id = if (out) selfId else peerId }
            flags = flags or 256
            dialog_id = peerId
            if (withPhoto) {
                flags = flags or 512
                media = TLRPC.TL_messageMediaPhoto().apply {
                    photo = TLRPC.TL_photo().apply {
                        this.id = 5_000_000L + id
                        access_hash = 1
                        this.date = date
                        dc_id = 2
                        file_reference = ByteArray(0)
                        sizes.add(TLRPC.TL_photoCachedSize().apply {
                            type = "s"
                            w = 40
                            h = 40
                            bytes = ByteArray(128) { (it * 3).toByte() }
                            location = TLRPC.TL_fileLocationToBeDeprecated().apply { volume_id = -id.toLong(); local_id = id }
                        })
                    }
                    flags = flags or 1
                }
            } else {
                media = TLRPC.TL_messageMediaEmpty()
            }
        }
    }

    private fun awaitMessagesDidLoad(guid: Int, cache: Boolean, start: (Int) -> Unit): List<MessageObject> {
        val latch = CountDownLatch(1)
        var result: List<MessageObject> = emptyList()
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                if (id == NotificationCenter.messagesDidLoad && args[10] == guid && args[3] == cache) {
                    @Suppress("UNCHECKED_CAST")
                    result = ArrayList(args[2] as ArrayList<MessageObject>)
                    latch.countDown()
                }
            }
        }
        onUi { NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.messagesDidLoad) }
        try {
            start(guid)
            assertTrue("messagesDidLoad was never delivered (cache=$cache)", latch.await(20, TimeUnit.SECONDS))
        } finally {
            onUi { NotificationCenter.getInstance(account).removeObserver(observer, NotificationCenter.messagesDidLoad) }
        }
        return result
    }

    private companion object {
        const val READ_OUTBOX_MAX = 40
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
        latch.await(10, TimeUnit.SECONDS)
    }
}
