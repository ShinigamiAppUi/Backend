package streamix.chat

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import streamix.auth.FileUserRepository

class FileChatRepositoryPrivacyTest {
    @Test
    fun directMessagesAndUnreadStateAreScopedToParticipants() {
        val root = Files.createTempDirectory("shinigami-chat-privacy")
        val users = FileUserRepository(root.resolve("users.json"))
        val alice = users.create("alice", "test", "alice")
        val bob = users.create("bob", "test", "bob")
        val mallory = users.create("mallory", "test", "mallory")
        val chat = FileChatRepository(root.resolve("chat"), users)

        chat.sendMessage(alice.id, bob.id, "private message")

        assertEquals(1, chat.messages(alice.id, bob.id, page = 1, perPage = 30).size)
        assertEquals(1, chat.messages(bob.id, alice.id, page = 1, perPage = 30).size)
        assertTrue(chat.messages(mallory.id, alice.id, page = 1, perPage = 30).isEmpty())
        assertTrue(chat.conversations(mallory.id, page = 1, perPage = 30).isEmpty())
        assertEquals(0, chat.unreadCount(mallory.id))
        assertEquals(0, chat.markRead(mallory.id, alice.id))
        assertEquals(1, chat.unreadCount(bob.id))
        assertEquals(1, chat.markRead(bob.id, alice.id))
        assertEquals(0, chat.unreadCount(bob.id))
    }

    @Test
    fun eitherUsersBlockPreventsDirectMessageCreation() {
        val root = Files.createTempDirectory("shinigami-chat-block")
        val users = FileUserRepository(root.resolve("users.json"))
        val alice = users.create("alice", "test", "alice")
        val bob = users.create("bob", "test", "bob")
        val chat = FileChatRepository(root.resolve("chat"), users)

        users.setRelationship(alice.id, bob.id, blocked = true)
        assertFailsWith<IllegalArgumentException> {
            chat.sendMessage(bob.id, alice.id, "blocked from recipient side")
        }

        users.setRelationship(alice.id, bob.id, blocked = false)
        users.setRelationship(bob.id, alice.id, blocked = true)
        assertFailsWith<IllegalArgumentException> {
            chat.sendMessage(alice.id, bob.id, "blocked from sender side")
        }
    }
}
