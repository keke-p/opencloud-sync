package io.github.ocmediasync.remote

import java.util.Base64

/** TUS の Upload-Metadata ヘッダー値 ("key base64value,key base64value") を作る。 */
object TusMetadata {
    fun encode(metadata: Map<String, String>): String =
        metadata.entries.joinToString(",") { (key, value) ->
            key + " " + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        }
}
