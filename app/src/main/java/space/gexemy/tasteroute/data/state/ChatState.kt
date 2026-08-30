package space.gexemy.tasteroute.data.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.ListSerializer
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.local.Prefs

private const val CHAT_LIMIT = 40

object ChatState {
    val chatMessages = mutableStateListOf<ChatMessage>()
    var assistantBusy by mutableStateOf(false)

    fun addChat(message: ChatMessage) {
        chatMessages.add(message.copy(me = message.fromUser))
        persistChat()
    }

    fun replaceChat(index: Int, message: ChatMessage) {
        if (index !in chatMessages.indices) return
        chatMessages[index] = message.copy(me = message.fromUser)
        persistChat()
    }

    fun clearChat() {
        chatMessages.clear()
        Prefs.remove(Prefs.CHAT_LOG)
    }

    private fun persistChat() {
        if (!PreferenceState.saveHistory) return
        val keep = chatMessages.takeLast(CHAT_LIMIT)
        Prefs.put(Prefs.CHAT_LOG, AppJson.encodeToString(ListSerializer(ChatMessage.serializer()), keep))
    }
}
