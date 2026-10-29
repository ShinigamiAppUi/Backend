package streamix.chat

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import java.util.concurrent.Executors
import streamix.auth.FileUserRepository

class FileChatRepositoryPaginationTest {
    @Test
    fun hugePageNumberReturnsEmptyListInsteadOfOverflowingOffset() {
        val root = Files.createTempDirectory("shinigami-chat-pagination")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create(
            username = "reader",
            externalProvider = "test",
            externalSubject = "reader"
        )
        val chat = FileChatRepository(root.resolve("chat"), users)
        chat.sendGlobal(user.id, "Pagination regression")

        assertTrue(chat.global(user.id, Int.MAX_VALUE, 100).isEmpty())
    }

    @Test
    fun fullFinalPageDoesNotClaimThereIsAnotherPage() {
        val root = Files.createTempDirectory("shinigami-chat-page-contract")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create(
            username = "reader",
            externalProvider = "test",
            externalSubject = "reader"
        )
        val repository = FileChatRepository(root.resolve("chat"), users)
        repository.sendGlobal(user.id, "One message")

        val page = ChatService(repository).global(user.id, page = 1, perPage = 1)
        assertFalse(page.hasNextPage)
        assertTrue(page.items.size == 1)
    }

    @Test
    fun concurrentSendsAcrossRepositoryInstancesPreserveEveryMessage() {
        val root = Files.createTempDirectory("shinigami-chat-concurrency")
        val users = FileUserRepository(root.resolve("users.json"))
        val user = users.create(username = "writer", externalProvider = "test", externalSubject = "writer")
        val first = FileChatRepository(root.resolve("chat"), users)
        val second = FileChatRepository(root.resolve("chat"), users)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..80).map { index ->
                java.util.concurrent.Callable {
                    (if (index % 2 == 0) first else second).sendGlobal(user.id, "message-$index")
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertEquals(80, first.global(user.id, 1, 101).size)
    }
}
