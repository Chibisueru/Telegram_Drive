package io.github.chibisueru.telegramdrive.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class TelegramException(message: String, val code: Int = 0) : IOException(message)

/** A document the bot posted: what we need to fetch it back and to delete it later. */
data class SentDocument(val messageId: Long, val fileId: String)

/**
 * Minimal Telegram Bot API client. Every call handles flood control (HTTP 429 with
 * `retry_after`) and retries transient network errors.
 *
 * Bot API limits that shape the rest of the app: uploads up to 50 MB, getFile downloads up
 * to 20 MB. Files are therefore stored as chunks of [CHUNK_SIZE] bytes.
 */
class BotApi(
    private val http: OkHttpClient,
    private val tokenProvider: suspend () -> String,
) {
    companion object {
        const val BASE = "https://api.telegram.org"
        /** 19 MiB — safely under the 20 MB getFile download limit. */
        const val CHUNK_SIZE: Int = 19 * 1024 * 1024
    }

    suspend fun getMe(token: String? = null): JSONObject =
        call("getMe", emptyMap(), token) as JSONObject

    suspend fun getChat(chatId: String, token: String? = null): JSONObject =
        call("getChat", mapOf("chat_id" to chatId), token) as JSONObject

    suspend fun getChatMember(chatId: String, userId: Long, token: String? = null): JSONObject =
        call("getChatMember", mapOf("chat_id" to chatId, "user_id" to userId), token) as JSONObject

    suspend fun getUpdates(token: String? = null): JSONArray =
        call(
            "getUpdates",
            mapOf("allowed_updates" to """["channel_post","my_chat_member","message"]"""),
            token,
        ) as JSONArray

    suspend fun getFilePath(fileId: String): String {
        val result = call("getFile", mapOf("file_id" to fileId)) as JSONObject
        return result.optString("file_path").ifEmpty { throw TelegramException("Telegram returned no file path") }
    }

    suspend fun fileUrl(filePath: String): String = "$BASE/file/bot${tokenProvider()}/$filePath"

    suspend fun pinChatMessage(chatId: String, messageId: Long) {
        call("pinChatMessage", mapOf("chat_id" to chatId, "message_id" to messageId, "disable_notification" to true))
    }

    /** Best effort: Telegram refuses to delete some messages (e.g. older than 48 h). */
    suspend fun deleteMessages(chatId: String, ids: List<Long>) {
        ids.chunked(100).forEach { batch ->
            runCatching {
                call("deleteMessages", mapOf("chat_id" to chatId, "message_ids" to JSONArray(batch).toString()))
            }
        }
    }

    /**
     * Uploads the first [length] bytes of [bytes] as a document. [onProgress] receives the
     * number of bytes sent so far (it may restart from 0 if the request is retried).
     */
    suspend fun sendDocument(
        chatId: String,
        fileName: String,
        bytes: ByteArray,
        length: Int,
        caption: String? = null,
        onProgress: (Long) -> Unit = {},
    ): SentDocument {
        val message = executeWithRetry("sendDocument", null) {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("disable_notification", "true")
                .addFormDataPart("disable_content_type_detection", "true")
                .apply { if (caption != null) addFormDataPart("caption", caption) }
                .addFormDataPart("document", fileName, ProgressBody(bytes, length, onProgress))
                .build()
        } as JSONObject
        // Telegram may classify a document as another media type despite our hints.
        val media = listOf("document", "video", "audio", "animation", "voice", "photo")
            .firstNotNullOfOrNull { key ->
                val v: Any? = message.opt(key)
                if (v is JSONObject) v else if (v is JSONArray) v.optJSONObject(v.length() - 1) else null
            } ?: throw TelegramException("Telegram returned a message without a file")
        return SentDocument(message.getLong("message_id"), media.getString("file_id"))
    }

    suspend fun call(method: String, params: Map<String, Any?> = emptyMap(), token: String? = null): Any? =
        executeWithRetry(method, token) {
            FormBody.Builder().apply {
                params.forEach { (k, v) -> if (v != null) add(k, v.toString()) }
            }.build()
        }

    private suspend fun executeWithRetry(method: String, token: String?, body: () -> RequestBody): Any? {
        val t = token ?: tokenProvider()
        if (t.isBlank()) throw TelegramException("No bot token configured")
        var networkFailures = 0
        var floodWaits = 0
        while (true) {
            val request = Request.Builder().url("$BASE/bot$t/$method").post(body()).build()
            val json = try {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { JSONObject(it.body.string()) }
                }
            } catch (e: IOException) {
                if (++networkFailures > 3) throw e
                delay(1500L * networkFailures)
                continue
            } catch (e: org.json.JSONException) {
                throw TelegramException("Unexpected response from Telegram")
            }
            if (json.optBoolean("ok")) return json.opt("result")

            val code = json.optInt("error_code")
            val description = json.optString("description", "Telegram error $code")
            if (code == 429 && ++floodWaits <= 10) {
                val retryAfter = json.optJSONObject("parameters")?.optInt("retry_after", 5) ?: 5
                delay((retryAfter + 1) * 1000L)
                continue
            }
            throw TelegramException(description, code)
        }
    }

    private class ProgressBody(
        private val bytes: ByteArray,
        private val length: Int,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = length.toLong()
        override fun writeTo(sink: BufferedSink) {
            var offset = 0
            onProgress(0)
            while (offset < length) {
                val n = minOf(64 * 1024, length - offset)
                sink.write(bytes, offset, n)
                offset += n
                onProgress(offset.toLong())
            }
        }
    }
}
