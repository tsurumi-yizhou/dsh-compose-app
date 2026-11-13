package dev.fughetta.dshcompose.ui.conversation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.attachments.*
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.drafts.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsStateTest {
    private class Drafts : DraftsRepository {
        override val state = MutableStateFlow(DraftsState(loaded = true))
        val writes = mutableListOf<Pair<String, String>>()

        override suspend fun save(sessionId: String, text: String) {
            writes += sessionId to text
        }

        override suspend fun forget(sessionId: String) {}
    }

    private class History : ConversationRepository {
        override fun publications(
            isCurrentConnection: () -> Boolean,
            usage: dev.fughetta.dshcompose.domain.usage.UsageRepository?,
        ): kotlinx.coroutines.flow.Flow<
            dev.fughetta.dshcompose.domain.sessions.TranscriptSnapshot,
        > = error("unused")

        var closes = 0

        override fun close() {
            closes++
        }

        override suspend fun confirmModel(selection: ModelSelection) {}

        override suspend fun confirmAgentPreset(preset: String) {}

        override suspend fun confirmCommand(
            result: dev.fughetta.dshcompose.domain.commands.CommandResult,
            line: String,
        ) {}

        override suspend fun pendingApprovals(callIds: Set<String>) {}

        override suspend fun retryForNotice(
            key: String,
        ): dev.fughetta.dshcompose.domain.messages.TranscriptRetryPlan? = null

        var branch: suspend (Long) -> Long? = { null }

        override suspend fun branchPointAfter(seq: Long): Long? = branch(seq)

        override suspend fun branchPointBefore(seq: Long): Long? = null

        override val historyEpoch = MutableStateFlow(1L)
        val answers = Channel<Result<Unit>>(Channel.UNLIMITED)
        var reads = 0
        var cancelled = 0

        override suspend fun loadHistory() {
            reads++
            try {
                answers.receive().getOrThrow()
            } catch (error: CancellationException) {
                cancelled++
                throw error
            }
        }
    }

    private class Subscription(
        val repository: History,
        val updates: Flow<ConversationPublication>,
    ) {
        val closes: Int
            get() = repository.closes
    }

    private fun ConversationViewModel.bind(id: String, source: Subscription) =
        bind(id, source.repository, source.updates)

    private fun publication(revision: Long, title: String) =
        ConversationPublication(
            ChatUiState(
                messageRevision = revision,
                historyEpoch = 1,
                title = title,
                syncing = false,
            ),
            false,
            "/home",
            false,
            false,
        )

    @Test
    fun messageSelectionAndActionsRetireWithTheirConversationAndHistory() = runTest {
        val conversations =
            ConversationsState(ComposerState(Drafts(), backgroundScope), ComposerAttachmentsState())
        conversations.update("session") {
            it.copy(
                historyEpoch = 1,
                messages =
                    persistentListOf(
                        ChatItem("prompt", ChatRole.User, "Question", seq = 1),
                        ChatItem("first", ChatRole.Assistant, "First", seq = 2),
                        ChatItem("second", ChatRole.Assistant, "Second", seq = 3),
                    ),
            )
        }
        val actions = ConversationViewModel(conversations, backgroundScope)
        val oldAnswer = CompletableDeferred<Long?>()
        val first =
            History().apply { branch = { withContext(NonCancellable) { oldAnswer.await() } } }
        actions.bindMessages("session", first)
        actions.openMessage("session", "first")
        runCurrent()
        assertNull(actions.messageMenu.value)
        val second = History().apply { branch = { 30 } }
        actions.bindMessages("session", second)
        actions.openMessage("session", "second")
        runCurrent()
        oldAnswer.complete(20)
        runCurrent()
        assertEquals("second", actions.messageMenu.value!!.row.key)
        assertEquals(30L, actions.messageMenu.value!!.branchCut)
        assertEquals("prompt", actions.messageMenu.value!!.regeneratePrompt!!.key)
        val pending = CompletableDeferred<Unit>()
        var applied = false
        actions.performMessage(MessageOperation.Branch) {
            withContext(NonCancellable) { pending.await() }
            if (isCurrent()) applied = true
        }
        runCurrent()
        second.historyEpoch.value = 2
        runCurrent()
        assertNull(actions.messageMenu.value)
        pending.complete(Unit)
        runCurrent()
        assertFalse(applied)
        actions.openMessage("session", "second")
        runCurrent()
        assertNull(actions.messageMenu.value)
    }

    @Test
    fun messageActionsKeepBusyAndErrorsLocalAndReleaseBeforeChildNavigation() = runTest {
        val conversations =
            ConversationsState(ComposerState(Drafts(), backgroundScope), ComposerAttachmentsState())
        for (id in listOf("parent", "child")) conversations.update(id) {
            it.copy(
                historyEpoch = 1,
                messages = persistentListOf(ChatItem(id, ChatRole.User, "Prompt", seq = 1)),
            )
        }
        val actions = ConversationViewModel(conversations, backgroundScope)
        val repository = History()
        actions.bindMessages("parent", repository)
        actions.openMessage("parent", "parent")
        runCurrent()
        val result = CompletableDeferred<Unit>()
        actions.performMessage(MessageOperation.Edit) { result.await() }
        runCurrent()
        actions.dismissMessage()
        assertTrue(actions.messageMenu.value!!.busy)
        var duplicate = false
        actions.performMessage(MessageOperation.Edit) { duplicate = true }
        result.completeExceptionally(IllegalStateException("host rejected"))
        runCurrent()
        assertFalse(duplicate)
        assertEquals("host rejected", actions.messageMenu.value!!.error)
        assertFalse(actions.messageMenu.value!!.busy)
        var navigated = false
        actions.performMessage(MessageOperation.Edit) {
            complete()
            actions.bindMessages("child", repository)
            actions.openMessage("child", "child")
            navigated = true
        }
        runCurrent()
        assertTrue(navigated)
        assertEquals("child", actions.messageMenu.value!!.sessionId)
        assertFalse(actions.messageMenu.value!!.busy)
        actions.dismissMessage()
        assertNull(actions.messageMenu.value)
    }

    @Test
    fun clearingViewModelRetiresSubscriptionAndPaginationWithoutCancellingSiblingWork() = runTest {
        val conversations =
            ConversationsState(ComposerState(Drafts(), backgroundScope), ComposerAttachmentsState())
        val store = ViewModelStore()
        val model =
            ViewModelProvider(
                store,
                viewModelFactory {
                    initializer { ConversationViewModel(conversations, backgroundScope) }
                },
            )[ConversationViewModel::class.java]
        val sibling = backgroundScope.launch { awaitCancellation() }
        val history = History()
        val updates = Channel<ConversationPublication>(Channel.UNLIMITED)
        val source = Subscription(history, updates.receiveAsFlow())
        model.bind("session", source)
        updates.send(publication(1, "Current"))
        runCurrent()
        model.loadHistory("session")
        runCurrent()
        assertEquals(1, history.reads)

        store.clear()
        runCurrent()

        assertEquals(1, source.closes)
        assertEquals(1, history.cancelled)
        assertNull(model.current)
        assertFalse(conversations.state.value.chats.getValue("session").loadingHistory)
        assertTrue(sibling.isActive)
        updates.close()
    }

    @Test
    fun replacingSubscriptionRetiresItsPaginationAndIgnoresItsLateFailure() = runTest {
        val conversations =
            ConversationsState(ComposerState(Drafts(), backgroundScope), ComposerAttachmentsState())
        val publications = mutableListOf<String>()
        val controller =
            ConversationViewModel(conversations, backgroundScope) { _, publication, _ ->
                publications += publication.view.title.orEmpty()
            }
        val retire = CompletableDeferred<Unit>()
        val firstHistory = History()
        val first =
            Subscription(
                firstHistory,
                flow {
                    emit(publication(1, "First"))
                    withContext(NonCancellable) { retire.await() }
                    throw IllegalStateException("retired stream failed")
                },
            )
        controller.bind("same-session", first)
        runCurrent()
        advanceTimeBy(32)
        runCurrent()
        controller.loadHistory("same-session")
        runCurrent()
        assertTrue(conversations.state.value.chats.getValue("same-session").loadingHistory)
        val updates = Channel<ConversationPublication>(Channel.UNLIMITED)
        val secondHistory = History()
        val second = Subscription(secondHistory, updates.receiveAsFlow())
        controller.bind("same-session", second)
        updates.send(publication(2, "Second"))
        runCurrent()
        assertEquals(1, first.closes)
        assertEquals(1, firstHistory.cancelled)
        assertFalse(conversations.state.value.chats.getValue("same-session").loadingHistory)
        retire.complete(Unit)
        runCurrent()
        assertSame(secondHistory, controller.current)
        assertTrue(controller.isActive("same-session"))
        assertNull(conversations.state.value.chats.getValue("same-session").error)
        assertEquals(listOf("First", "Second"), publications)
        controller.loadHistory("same-session")
        runCurrent()
        assertEquals(1, secondHistory.reads)
        controller.clear()
        controller.clear()
        runCurrent()
        assertEquals(1, second.closes)
        assertEquals(1, secondHistory.cancelled)
        assertNull(controller.current)
        assertFalse(controller.isActive("same-session"))
    }

    @Test
    fun subscriptionCompletionAndFailureReleaseTheirSourceAndHistoryBinding() = runTest {
        val conversations =
            ConversationsState(ComposerState(Drafts(), backgroundScope), ComposerAttachmentsState())
        val publications = mutableListOf<Long>()
        val controller =
            ConversationViewModel(conversations, this) { _, publication, _ ->
                publications += publication.view.messageRevision
            }
        val repository = History()
        val completed =
            Subscription(
                repository,
                flow {
                    emit(publication(1, "First"))
                    emit(publication(1, "Stale"))
                    emit(publication(2, "Latest"))
                },
            )
        controller.bind("session", completed)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), publications)
        assertEquals("Latest", conversations.state.value.chats.getValue("session").title)
        assertEquals(1, completed.closes)
        assertNull(controller.current)
        controller.loadHistory("session")
        runCurrent()
        assertEquals(0, repository.reads)
        val failed = Subscription(History(), flow { throw IllegalStateException("stream lost") })
        controller.bind("session", failed)
        advanceUntilIdle()
        assertEquals(1, failed.closes)
        assertNull(controller.current)
        assertEquals("stream lost", conversations.state.value.chats.getValue("session").error)
        assertTrue(conversations.state.value.chats.getValue("session").syncing)
    }

    @Test
    fun transcriptPublicationKeepsCurrentInputAndPageStateAndRejectsOlderContent() = runTest {
        val drafts = Drafts()
        val composer = ComposerState(drafts, backgroundScope)
        val files = ComposerAttachmentsState()
        val conversations = ConversationsState(composer, files)
        composer.edit("session") {
            it.copy(draft = "Still typing", quote = "Selected quote", draftCursor = 5)
        }
        files.add(
            "session",
            listOf(
                ComposerFile(
                    "file",
                    "Selected",
                    "text/plain",
                    (File("selected.txt")).absolutePath,
                    20,
                ),
            ),
        )
        conversations.foldCompletedTurns(false)
        conversations.update("session") {
            it.copy(
                messageRevision = 5,
                historyEpoch = 1,
                loadingHistory = true,
                sending = true,
                attachments = it.attachments.map { file -> file.copy(progress = 0.5f) },
            )
        }
        val writes = drafts.writes.toList()
        val view =
            ChatUiState(
                messageRevision = 10,
                historyEpoch = 1,
                messages = persistentListOf(ChatItem("message", ChatRole.Assistant, "Current")),
                title = "Host title",
                syncing = false,
                hasMoreHistory = true,
                draft = "Obsolete input",
            )
        conversations.publishTranscript("session", view, false)
        val published = conversations.state.value.chats.getValue("session")
        assertEquals("Still typing", published.draft)
        assertEquals("Selected quote", published.quote)
        assertEquals(5, published.draftCursor)
        assertEquals(0.5f, published.attachments.single().progress)
        assertTrue(published.sending)
        assertTrue(published.loadingHistory)
        assertFalse(published.foldCompletedTurns)
        assertSame(view.messages, published.messages)
        val before = conversations.state.value
        conversations.publishTranscript(
            "session",
            view.copy(messageRevision = 9, title = "Older"),
            true,
        )
        assertSame(before, conversations.state.value)
        conversations.publishTranscript(
            "session",
            view.copy(messageRevision = 11, historyEpoch = 2),
            true,
        )
        assertFalse(conversations.state.value.chats.getValue("session").loadingHistory)
        assertTrue(conversations.state.value.chats.getValue("session").readOnly)
        assertEquals(writes, drafts.writes)
    }

    @Test
    fun leavingConversationReleasesTranscriptAndFencesOlderPublicationsWhileRetainingInput() =
        runTest {
            val composer = ComposerState(Drafts(), backgroundScope)
            val conversations = ConversationsState(composer, ComposerAttachmentsState())
            composer.edit("session") { it.copy(draft = "Keep", quote = "Quote") }
            val view =
                ChatUiState(
                    messageRevision = 10,
                    historyEpoch = 1,
                    messages = persistentListOf(ChatItem("message", ChatRole.User, "History")),
                    mediaRevision = 7,
                    generatedMediaPaths = persistentSetOf("artifact"),
                    hasMoreHistory = true,
                    syncing = false,
                )
            conversations.publishTranscript("session", view, false)
            val retained = conversations.state.value
            conversations.releaseTranscript("session", 20)
            val released = conversations.state.value.chats.getValue("session")
            assertTrue(released.messages.isEmpty())
            assertTrue(released.generatedMediaPaths.isEmpty())
            assertEquals(0L, released.mediaRevision)
            assertFalse(released.hasMoreHistory)
            assertTrue(released.syncing)
            assertEquals("Keep", released.draft)
            assertEquals("Quote", released.quote)
            assertEquals("History", retained.chats.getValue("session").messages.single().text)
            val before = conversations.state.value
            conversations.publishTranscript("session", view, false)
            assertSame(before, conversations.state.value)
            conversations.publishTranscript(
                "session",
                view.copy(messageRevision = 21, historyEpoch = 2),
                false,
            )
            assertEquals(
                "History",
                conversations.state.value.chats.getValue("session").messages.single().text,
            )
            assertEquals("Keep", conversations.state.value.chats.getValue("session").draft)
        }

    @Test
    fun historyIntentSharesItsReadAndLeavesContentToThePublicationOwner() = runTest {
        val composer = ComposerState(Drafts(), backgroundScope)
        val conversations = ConversationsState(composer, ComposerAttachmentsState())
        conversations.update("session") { it.copy(hasMoreHistory = true) }
        conversations.update("other") { it.copy(title = "Untouched") }
        val other = conversations.state.value.chats.getValue("other")
        val repository = History()
        val history = ConversationViewModel(conversations, backgroundScope)
        history.bindHistory("session", repository)
        history.loadHistory("session")
        history.loadHistory("session")
        runCurrent()
        assertEquals(1, repository.reads)
        assertTrue(conversations.state.value.chats.getValue("session").loadingHistory)
        val published =
            kotlinx.collections.immutable.persistentListOf(
                ChatItem("live", ChatRole.Assistant, "Latest"),
            )
        conversations.update("session") { it.copy(messageRevision = 20, messages = published) }
        repository.answers.send(Result.success(Unit))
        runCurrent()
        assertSame(published, conversations.state.value.chats.getValue("session").messages)
        assertFalse(conversations.state.value.chats.getValue("session").loadingHistory)
        history.loadHistory("session")
        runCurrent()
        repository.answers.send(Result.failure(SessionOperationException("history/denied")))
        runCurrent()
        assertEquals("history/denied", conversations.state.value.chats.getValue("session").error)
        assertFalse(conversations.state.value.chats.getValue("session").loadingHistory)
        assertSame(other, conversations.state.value.chats.getValue("other"))
    }

    @Test
    fun historyEpochAndConversationChangesCancelOnlyTheirOwnPagination() = runTest {
        val conversations =
            ConversationsState(ComposerState(Drafts(), backgroundScope), ComposerAttachmentsState())
        conversations.update("a") { it.copy(hasMoreHistory = true) }
        conversations.update("b") { it.copy(hasMoreHistory = true) }
        val first = History()
        val second = History()
        val history = ConversationViewModel(conversations, backgroundScope)
        history.bindHistory("a", first)
        history.loadHistory("a")
        runCurrent()
        first.historyEpoch.value = 2
        runCurrent()
        assertEquals(1, first.cancelled)
        assertFalse(conversations.state.value.chats.getValue("a").loadingHistory)
        history.loadHistory("a")
        runCurrent()
        assertEquals(2, first.reads)
        history.bindHistory("b", second)
        conversations.remove("a")
        history.loadHistory("b")
        runCurrent()
        assertEquals(2, first.cancelled)
        first.answers.send(Result.success(Unit))
        second.answers.send(Result.success(Unit))
        runCurrent()
        assertFalse(conversations.state.value.chats.containsKey("a"))
        assertFalse(conversations.state.value.chats.getValue("b").loadingHistory)
    }

    @Test
    fun draftPromotionPreservesComposerAndLeavesOtherChatsAlone() = runTest {
        val composer = ComposerState(Drafts(), backgroundScope)
        val files = ComposerAttachmentsState()
        val conversations = ConversationsState(composer, files)
        composer.edit(DRAFT_SESSION_ID) {
            it.copy(draft = "hello", quote = "quote", draftCursor = 3)
        }
        val file =
            ComposerFile("file", "name", "text/plain", (File("selected.txt")).absolutePath, 20)
        files.add(DRAFT_SESSION_ID, listOf(file))
        conversations.update(DRAFT_SESSION_ID) { it.copy(sending = true) }
        conversations.update("other") { it.copy(title = "other") }
        val other = conversations.state.value.chats.getValue("other")
        files.move(DRAFT_SESSION_ID, "host-created")
        composer.promote(DRAFT_SESSION_ID, "host-created")
        // Input projections can run before the session-creation callback promotes presentation.
        conversations.syncComposer()
        conversations.syncAttachments()
        conversations.promoteDraft("host-created")
        val promoted = conversations.state.value.chats
        assertFalse(promoted.containsKey(DRAFT_SESSION_ID))
        assertEquals("hello", promoted.getValue("host-created").draft)
        assertEquals("quote", promoted.getValue("host-created").quote)
        assertEquals(3, promoted.getValue("host-created").draftCursor)
        assertFalse(promoted.getValue("host-created").sending)
        assertEquals("file", promoted.getValue("host-created").attachments.single().id)
        assertSame(other, promoted["other"])
    }

    @Test
    fun presentationUpdatesPreserveInputsAndNeverWriteDrafts() = runTest {
        val drafts = Drafts()
        val composer = ComposerState(drafts, backgroundScope)
        val files = ComposerAttachmentsState()
        val conversations = ConversationsState(composer, files)
        composer.edit("session") { it.copy(draft = "captured") }
        val beforeWrites = drafts.writes.toList()
        files.add(
            "session",
            listOf(
                ComposerFile("file", "name", "text/plain", (File("selected.txt")).absolutePath, 20),
            ),
        )
        conversations.update("session") {
            it.copy(
                title = "loaded history",
                messageRevision = 1,
                sending = true,
                attachments = it.attachments.map { file -> file.copy(progress = 0.5f) },
            )
        }
        val loaded = conversations.state.value
        conversations.reconnecting()
        val reconnected = conversations.state.value.chats.getValue("session")
        assertFalse(reconnected.sending)
        assertNull(reconnected.attachments.single().progress)
        assertEquals(1L, reconnected.messageRevision)
        assertTrue(loaded.chats.getValue("session").sending)
        conversations.foldCompletedTurns(false)
        assertEquals("captured", conversations.state.value.chats.getValue("session").draft)
        assertFalse(conversations.state.value.chats.getValue("session").foldCompletedTurns)
        assertEquals(beforeWrites, drafts.writes)
        val before = conversations.state.value
        conversations.syncComposer()
        conversations.syncAttachments()
        assertSame(before, conversations.state.value)
        conversations.remove("session")
        assertTrue(conversations.state.value.chats.isEmpty())
        conversations.update("next") { it }
        assertFalse(conversations.state.value.chats.getValue("next").foldCompletedTurns)
        conversations.reset()
        assertTrue(conversations.state.value.chats.isEmpty())
        assertEquals(beforeWrites, drafts.writes)
    }
}
