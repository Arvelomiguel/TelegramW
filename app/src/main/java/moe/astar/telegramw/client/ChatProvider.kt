package moe.astar.telegramw.client

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import kotlin.concurrent.withLock

class ChatProvider @Inject constructor(private val client: TelegramClient) {

    private val TAG = this::class.simpleName

    // remove of ConcurrentSkipListSet didn't work as expected. Instead, synchronize
    // access to non-threadsafe SortedSet with a ReentrantLock
    private val chatOrdering =
        sortedSetOf<Pair<Long, Long>>(comparator = { a, b -> if (a.second < b.second) 1 else -1 })
    private val chatOrderingLock = ReentrantLock()

    private val _chatIds = MutableStateFlow(listOf<Long>())
    val chatIds: StateFlow<List<Long>> get() = _chatIds

    private val _chatData = MutableStateFlow(persistentHashMapOf<Long, TdApi.Chat>())
    val chatData: StateFlow<PersistentMap<Long, TdApi.Chat>> get() = _chatData

    private val _threads = MutableStateFlow<PersistentSet<Long>>(persistentHashSetOf())
    val threads: StateFlow<PersistentSet<Long>> get() = _threads

    private val _threadData = MutableStateFlow(persistentHashMapOf<Long, TdApi.ForumTopics>())
    val threadData: StateFlow<PersistentMap<Long, TdApi.ForumTopics>> get() = _threadData


    private val scope = CoroutineScope(Dispatchers.Default)

    private fun updateProperty(chatId: Long, update: (TdApi.Chat) -> Unit) {
        _chatData.update { data ->
            data[chatId]?.let { chat ->
                update(chat)
                data.put(chatId, chat) // Re-putting the same instance into persistent map still returns a new map if it was updated or we can just force update.
            } ?: data
        }
    }

    private fun isForum(chat: TdApi.Chat): Boolean {
        return (chat.type as? TdApi.ChatTypeSupergroup)?.let {
            client.getSupergroup(it.supergroupId)?.isForum
        } ?: false
    }

    init {
        client.updateFlow.onEach {
            //Log.d(TAG, it.toString())
            when (it) {
                is TdApi.UpdateChatPosition -> {
                    updateChatPositions(it.chatId, arrayOf(it.position))
                }
                is TdApi.UpdateChatLastMessage -> {

                    updateProperty(it.chatId) { chat ->
                        chat.apply { lastMessage = it.lastMessage }
                    }

                    updateChatPositions(it.chatId, it.positions)
                }
                is TdApi.UpdateChatTitle -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { title = it.title }
                    }
                    updateChats()
                }
                is TdApi.UpdateNewChat -> {
                    val newChat = it.chat
                    _chatData.update { data -> data.put(newChat.id, newChat) }
                    updateChatPositions(newChat.id, newChat.positions)
                }
                is TdApi.UpdateChatReadInbox -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply {
                            lastReadInboxMessageId = it.lastReadInboxMessageId
                            unreadCount = it.unreadCount
                        }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatReadOutbox -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { lastReadOutboxMessageId = it.lastReadOutboxMessageId }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatPhoto -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { photo = it.photo }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatUnreadMentionCount -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { unreadMentionCount = it.unreadMentionCount }
                    }
                    updateChats()
                }
                is TdApi.UpdateMessageMentionRead -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { unreadMentionCount = it.unreadMentionCount }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatReplyMarkup -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { replyMarkupMessageId = it.replyMarkupMessageId }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatDraftMessage -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { draftMessage = it.draftMessage }
                    }
                    updateChatPositions(it.chatId, it.positions)
                }
                is TdApi.UpdateChatPermissions -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { permissions = it.permissions }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatNotificationSettings -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { notificationSettings = it.notificationSettings }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatDefaultDisableNotification -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { defaultDisableNotification = it.defaultDisableNotification }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatIsMarkedAsUnread -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { isMarkedAsUnread = it.isMarkedAsUnread }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatIsBlocked -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { isBlocked = it.isBlocked }
                    }
                    updateChats()
                }
                is TdApi.UpdateChatHasScheduledMessages -> {
                    updateProperty(it.chatId) { chat ->
                        chat.apply { hasScheduledMessages = it.hasScheduledMessages }
                    }
                    updateChats()
                }
            }
        }.launchIn(scope)
    }

    private fun syncThread(chat: TdApi.Chat) {
        if (!isForum(chat)) return
        val chatId = chat.id
        scope.launch {
            val topicsFlow: Flow<TdApi.ForumTopics> =
                client.sendRequest(TdApi.GetForumTopics(chatId, "", 0, 0, 0, Int.MAX_VALUE))
                    .filterIsInstance()
            topicsFlow.collect { topics ->
                if (topics.topics == null) {
                    _threads.update { it.remove(chatId) }
                    _threadData.update { it.remove(chatId) }
                } else {
                    _threads.update { it.add(chatId) }
                    _threadData.update { it.put(chatId, topics) }
                }
            }
        }
    }

    fun loadChats() {
        scope.launch {
            client.sendRequest(TdApi.LoadChats(TdApi.ChatListMain(), Int.MAX_VALUE))
                .collect {}
        }
    }

    private fun updateChats() {
        chatOrderingLock.withLock {
            val chatIds = chatOrdering.toList().map { it.first }
            _chatIds.value = chatIds
            chatIds.forEach { id ->
                _chatData.value[id]?.let { chat ->
                    if (isForum(chat) && !_threadData.value.contains(id)) {
                        syncThread(chat)
                    }
                }
            }
        }
    }

    private fun updateChatPositions(chatId: Long, positions: Array<TdApi.ChatPosition>) {
        chatOrderingLock.withLock {
            chatOrdering.removeIf { it.first == chatId }
            positions.dropWhile { it.list !is TdApi.ChatListMain }
                .firstOrNull()?.order?.also { order ->
                    chatOrdering.add(Pair(chatId, order))
                }
        }
        
        _chatData.value[chatId]?.let { chat ->
            if (isForum(chat)) {
                syncThread(chat)
            }
        }
        
        updateChats()
    }

    fun getChat(chatId: Long): TdApi.Chat? {
        return _chatData.value[chatId]
    }
}
