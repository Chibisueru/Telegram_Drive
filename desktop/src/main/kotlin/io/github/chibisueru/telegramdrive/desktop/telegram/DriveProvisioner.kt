package io.github.chibisueru.telegramdrive.desktop.telegram

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageDocument

data class DriveChannel(val chatId: Long, val title: String)

/** Finds the user's drive channel, or creates it. Files live there as ordinary messages. */
class DriveProvisioner(private val client: TdlClient) {
    companion object {
        const val CHANNEL_TITLE = "Telegram Drive"
        /** Marks channels made by this app; also explains the channel to the user. */
        const val CHANNEL_MARKER = "#tgdrive"
        private val INDEX_REGEX = Regex("""index:(\d+)""")

        /** The channel description: an explanation, the marker, and where the latest index is. */
        fun describe(indexMessageId: Long? = null) =
            "Storage for the Telegram Drive app. Don't post, edit or delete messages here. $CHANNEL_MARKER" +
                (indexMessageId?.let { " index:$it" } ?: "")

        const val INDEX_FILE_NAME = "tgdrive_index.json"

        /** True for a posted (not still sending) index document in the drive channel. */
        fun isIndexMessage(m: Message) =
            m.sendingState == null && (m.content as? MessageDocument)?.document?.fileName == INDEX_FILE_NAME

        /**
         * The newest index posted by any device. Opens the channel first: TDLib only receives a
         * channel's updates while it is "open", so otherwise other devices' posts arrive late.
         * Checks the latest messages as well as the description pointer, which TDLib caches.
         */
        suspend fun latestIndexMessageId(client: TdlClient, chatId: Long): Long? {
            client.openChat(chatId)
            val fromHistory = (client.getChatHistory(chatId, 0, 0, 30, false) as? TdlResult.Success)
                ?.result?.messages?.filterNotNull()?.filter { isIndexMessage(it) }?.maxOfOrNull { it.id }
            val fromDescription = indexMessageId(client, chatId)
            return listOfNotNull(fromHistory, fromDescription).maxOrNull()
        }

        /** Reads the latest index message ID from the channel description. */
        suspend fun indexMessageId(client: TdlClient, chatId: Long): Long? {
            val type = (client.getChat(chatId) as? TdlResult.Success)?.result?.type as? ChatTypeSupergroup ?: return null
            val info = (client.getSupergroupFullInfo(type.supergroupId) as? TdlResult.Success)?.result ?: return null
            return INDEX_REGEX.find(info.description)?.groupValues?.get(1)?.toLongOrNull()
        }
    }

    /** Looks through the user's own channels for one created by this app. */
    suspend fun findExistingDrive(): DriveChannel? {
        val lists = listOf(ChatListMain(), ChatListArchive())
        for (list in lists) {
            for (i in 0 until 20) if (client.loadChats(list, 100) is TdlResult.Failure) break
        }
        val ids = lists.flatMap { list ->
            (client.getChats(list, 2000) as? TdlResult.Success)?.result?.chatIds?.toList().orEmpty()
        }.distinct()
        for (id in ids) {
            val chat = (client.getChat(id) as? TdlResult.Success)?.result ?: continue
            val type = chat.type as? ChatTypeSupergroup ?: continue
            if (!type.isChannel) continue
            val info = (client.getSupergroupFullInfo(type.supergroupId) as? TdlResult.Success)?.result ?: continue
            if (CHANNEL_MARKER in info.description) return DriveChannel(chat.id, chat.title)
        }
        return null
    }

    /** Creates the private storage channel. Only the user is a member. */
    suspend fun createChannel(): DriveChannel {
        val chat = client.createNewSupergroupChat(
            title = CHANNEL_TITLE,
            isForum = false,
            isChannel = true,
            description = describe(),
            messageAutoDeleteTime = 0,
            forImport = false,
        ).get()
        return DriveChannel(chat.id, chat.title)
    }
}
