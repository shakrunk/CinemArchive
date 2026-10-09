package work.kumarfamilynet.cinemarchive.data

import java.io.File
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import work.kumarfamilynet.cinemarchive.core.model.TicketAttachment

/** Blocking IO boundary; the transport pins one owner token and fences every stage. */
internal interface TicketAttachmentRemote {
    val projectId: String
    fun rpc(name: String, args: String, token: String, current: () -> Unit): String
    fun outing(id: String, owner: String, token: String, current: () -> Unit): String
    fun upload(attachment: TicketAttachment, file: File, token: String, current: () -> Unit)
    fun <T> download(attachment: TicketAttachment, token: String, current: () -> Unit, consume: (InputStream) -> T): T
}

/** Original objects are immutable; redirects are refused rather than forwarding private headers. */
internal class SupabaseTicketAttachmentRemote(
    override val projectId: String,
    private val anonKey: String,
    httpClient: OkHttpClient = OkHttpClient(),
) : TicketAttachmentRemote {
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val root = projectId.trimEnd('/')
    private val rest = SupabaseRestClient(root, anonKey, http)

    override fun rpc(name: String, args: String, token: String, current: () -> Unit): String {
        current()
        return rest.rpc(name, args, token).also { current() }
    }

    override fun outing(id: String, owner: String, token: String, current: () -> Unit): String {
        checkedTicketUuid(id); checkedTicketUuid(owner); current()
        return rest.get("cinema_outings", "id=eq.$id&user_id=eq.$owner&select=*", token).also { current() }
    }

    override fun upload(attachment: TicketAttachment, file: File, token: String, current: () -> Unit) {
        current()
        val body = object : RequestBody() {
            override fun contentType() = attachment.mimeType.toMediaType()
            override fun contentLength() = attachment.byteLength
            override fun writeTo(sink: BufferedSink) {
                var count = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        current()
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        require(count <= attachment.byteLength)
                        sink.write(buffer, 0, size)
                    }
                }
                require(count == attachment.byteLength); current()
            }
        }
        val request = request("object/ticket-attachments/${attachment.objectKey}", token)
            .header("x-upsert", "false").post(body).build()
        http.newCall(request).execute().use { response ->
            current()
            if (!response.isSuccessful) throw failure(response.code, response.body?.string().orEmpty())
        }
        current()
    }

    override fun <T> download(attachment: TicketAttachment, token: String, current: () -> Unit, consume: (InputStream) -> T): T {
        current()
        val request = request("object/authenticated/ticket-attachments/${attachment.objectKey}", token).get().build()
        return http.newCall(request).execute().use { response ->
            current()
            if (!response.isSuccessful) throw failure(response.code, response.body?.string().orEmpty())
            val body = checkNotNull(response.body)
            require(body.contentType()?.let { "${it.type}/${it.subtype}" } == attachment.mimeType) { "Downloaded ticket has an unexpected image format." }
            require(body.contentLength() == -1L || body.contentLength() == attachment.byteLength) { "Downloaded ticket has an unexpected size." }
            consume(body.byteStream()).also { current() }
        }
    }

    private fun request(path: String, token: String) = Request.Builder().url("$root/storage/v1/$path")
        .header("apikey", anonKey).header("Authorization", "Bearer $token")
    private fun failure(status: Int, body: String) = SupabaseHttpException(status, body, "Ticket storage request failed: HTTP $status")
}
