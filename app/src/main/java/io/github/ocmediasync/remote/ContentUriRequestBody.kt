package io.github.ocmediasync.remote

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FileNotFoundException
import java.io.IOException

/** ローカルファイル側の問題 (削除された、読み取り中に縮んだ等)。そのファイルだけ失敗扱いにする */
class LocalFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * content:// URI の [offset] から [length] バイトを送るリクエストボディ。
 * 書き込みのたびにファイルを開き直すので、再送 (401 後のリトライ等) にも対応する。
 */
class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
    private val mediaType: MediaType? = OCTET_STREAM,
    private val onProgress: ((bytesWritten: Long) -> Unit)? = null,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            if (offset > 0) input.channel.position(offset)
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L
            while (written < length) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                if (read == -1) throw LocalFileException("ファイルが途中で終わっています (変更または削除された可能性があります)")
                sink.write(buffer, 0, read)
                written += read
                onProgress?.invoke(written)
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val OFFSET_OCTET_STREAM = "application/offset+octet-stream".toMediaType()
    }
}
