package moe.astar.telegramw.client

import android.util.Log
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

class MessageProvider @Inject constructor(
    private val client: TelegramClient,
) {

    private val TAG = this::class.simpleName

    private var chatId: Long = -1
    private var threadId: Long? = null

    private val oldestMessageId = AtomicLong(0)
    private val lastQueriedMessageId = AtomicLong(-1)

    private val _messageIds = MutableStateFlow(persistentListOf<Long>())
    val messageIds: StateFlow<PersistentList<Long>> get() = _messageIds

    private val _messageData = MutableStateFlow(persistentHashMapOf<Long, TdApi.Message>())
    val messageData: StateFlow<PersistentMap<Long, TdApi.Message>> get() = _messageData

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var updateJob: Job? = null

    fun initialize(chatId: Long, threadId: Long?) {
        this.chatId = chatId
        this.threadId = threadId

        Log.d("MessageProvider", "threadId: " + this.threadId.toString())

        updateJob?.cancel()
        updateJob = scope.launch {
            launch {
                client.updateFlow
                    .filterIsInstance<TdApi.UpdateNewMessage>()
                    .filter { it.message.chatId == chatId && (threadId == null || threadId == it.message.messageThreadId) }
                    .collect { update ->
                        _messageData.update { data ->
                            if (!data.contains(update.message.id)) {
                                _messageIds.update { it.add(0, update.message.id) }
                            }
                            data.put(update.message.id, update.message)
                        }
                    }
            }

            launch {
                client.updateFlow
                    .filterIsInstance<TdApi.UpdateMessageSendSucceeded>()
                    .filter { it.message.chatId == chatId && (threadId == null || threadId == it.message.messageThreadId) }
                    .collect { update ->
                        _messageData.update { data ->
                            if (data.contains(update.oldMessageId)) {
                                _messageIds.update { ids ->
                                    if (ids.contains(update.oldMessageId)) {
                                        ids.set(ids.indexOf(update.oldMessageId), update.message.id)
                                    } else {
                                        ids.add(0, update.message.id)
                                    }
                                }
                                data.remove(update.oldMessageId).put(update.message.id, update.message)
                            } else {
                                if (!data.contains(update.message.id)) {
                                    _messageIds.update { it.add(0, update.message.id) }
                                    data.put(update.message.id, update.message)
                                } else data
                            }
                        }
                    }
            }

            launch {
                client.updateFlow
                    .filterIsInstance<TdApi.UpdateDeleteMessages>()
                    .filter { it.chatId == chatId }
                    .filter { it.isPermanent }
                    .collect { update ->
                        _messageData.update { data ->
                            var newData = data
                            val removeList = mutableListOf<Long>()
                            update.messageIds.forEach { id ->
                                if (newData.contains(id)) {
                                    newData = newData.remove(id)
                                    removeList.add(id)
                                }
                            }
                            _messageIds.update { it.removeAll(removeList) }
                            newData
                        }
                    }
            }

            launch {
                client.updateFlow
                    .filterIsInstance<TdApi.UpdateMessageContent>()
                    .filter { it.chatId == chatId }
                    .collect { update ->
                        _messageData.update { data ->
                            data[update.messageId]?.let { msg ->
                                msg.content = update.newContent
                                data.remove(update.messageId).put(update.messageId, msg)
                            } ?: data
                        }
                    }
            }

            launch {
                client.updateFlow
                    .filterIsInstance<TdApi.UpdateMessageInteractionInfo>()
                    .filter { it.chatId == chatId }
                    .collect { update ->
                        _messageData.update { data ->
                            data[update.messageId]?.let { msg ->
                                msg.interactionInfo = update.interactionInfo
                                data.remove(update.messageId).put(update.messageId, msg)
                            } ?: data
                        }
                    }
            }
        }
    }

    fun pullMessages(limit: Int = 10) {
        if (lastQueriedMessageId.get() != oldestMessageId.get()) {
            val msgId = oldestMessageId.get()
            lastQueriedMessageId.set(msgId)

            val messageSource = getMessages(msgId, limit)
            scope.launch {
                messageSource.collect { messages ->
                    _messageData.update { it.putAll(messages.associateBy { m -> m.id }) }
                    _messageIds.update { it.addAll(messages.map { m -> m.id }) }

                    _messageIds.value.lastOrNull()?.also { id ->
                        if (oldestMessageId.get() != id) {
                            oldestMessageId.set(id)
                        }
                    }
                }
            }
        }

    }

    private fun getMessages(fromMessageId: Long, limit: Int): Flow<List<TdApi.Message>> =
        if (threadId != null) {
            client.sendRequest(
                TdApi.GetMessageThreadHistory(
                    chatId,
                    threadId!!,
                    fromMessageId,
                    0,
                    limit
                )
            )
                .filterIsInstance<TdApi.Messages>()
                .map { it.messages.toList() }
        } else {
            client.sendRequest(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))
                .filterIsInstance<TdApi.Messages>()
                .map { it.messages.toList() }
        }


    fun sendMessageAsync(
        messageThreadId: Long = 0,
        replyToMessageId: Long = 0,
        options: TdApi.MessageSendOptions = TdApi.MessageSendOptions(),
        inputMessageContent: TdApi.InputMessageContent
    ): Deferred<TdApi.Message> = sendMessageAsync(
        TdApi.SendMessage(
            chatId,
            messageThreadId,
            replyToMessageId,
            options,
            null,
            inputMessageContent
        )
    )

    private fun sendMessageAsync(sendMessage: TdApi.SendMessage): Deferred<TdApi.Message> {
        val result = CompletableDeferred<TdApi.Message>()
        scope.launch {
            client.sendRequest(sendMessage).collect {
                when (it) {
                    is TdApi.Message -> result.complete(it)
                    is TdApi.Error -> {
                        Log.e(TAG, "Error sending message: ${it.code} - ${it.message}")
                        result.completeExceptionally(Exception(it.message))
                    }
                    else -> {
                        Log.e(TAG, "Unexpected response from TDLib: $it")
                        result.completeExceptionally(Exception("Unexpected response: $it"))
                    }
                }
            }
        }
        return result
    }

    fun updateSeenItems(items: List<Long>) {
        Log.d(TAG, items.toString())
        client.sendUnscopedRequest(
            TdApi.ViewMessages(
                chatId,
                items.toLongArray(),
                TdApi.MessageSourceChatHistory(),
                true
            )
        )
    }
}