package io.github.ocmediasync.remote

import android.net.Uri
import android.util.Xml
import io.github.ocmediasync.account.AuthRequiredException
import io.github.ocmediasync.account.TokenSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.concurrent.TimeUnit

/** サーバーが HTTP エラーを返した (通信自体は成功している) */
class RemoteHttpException(val code: Int, message: String) : IOException(message)

data class RemoteEntry(val isCollection: Boolean, val size: Long)

/** OpenCloud の WebDAV / TUS / Graph API の最小限のクライアント。すべてブロッキング。 */
class OpenCloudApi(serverUrl: String, tokens: TokenSource) {

    private val serverUrl: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()

    private val http: OkHttpClient = sharedClient.newBuilder()
        .addInterceptor(BearerAuthInterceptor(tokens))
        .build()

    /** パーソナルスペースの WebDAV ルート URL を Graph API から取得する */
    fun personalDriveDavUrl(): String {
        val url = serverUrl.newBuilder().addPathSegments("graph/v1.0/me/drives").build()
        val body = call(Request.Builder().url(url).get().build()).use { response ->
            if (!response.isSuccessful) throw httpError(response, "スペース一覧の取得")
            response.body!!.string()
        }
        val drives = JSONObject(body).getJSONArray("value")
        for (i in 0 until drives.length()) {
            val drive = drives.getJSONObject(i)
            if (drive.optString("driveType") == "personal") {
                return drive.getJSONObject("root").getString("webDavUrl")
            }
        }
        throw RemoteHttpException(404, "パーソナルスペースが見つかりません")
    }

    fun davUrl(davRoot: String, segments: List<String>): HttpUrl {
        val builder = davRoot.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    /** 存在しなければ null */
    fun stat(url: HttpUrl): RemoteEntry? {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "0")
            .build()
        return call(request).use { response ->
            when (response.code) {
                404 -> null
                207 -> parsePropfind(response.body!!.string())
                else -> throw httpError(response, "ファイル情報の取得")
            }
        }
    }

    /** davRoot 配下に segments のフォルダを順に作成する。作成済みのパスは cache に記録する。 */
    fun ensureDirectories(davRoot: String, segments: List<String>, cache: MutableSet<String>) {
        for (depth in 1..segments.size) {
            val url = davUrl(davRoot, segments.take(depth))
            val key = url.toString()
            if (key in cache) continue
            val entry = stat(url)
            when {
                entry == null -> mkcol(url)
                !entry.isCollection -> throw RemoteHttpException(
                    409, "同名のファイルがあるためフォルダを作成できません: ${segments.take(depth).joinToString("/")}",
                )
            }
            cache += key
        }
    }

    /** 直下のサブフォルダ名の一覧。フォルダが存在しなければ RemoteHttpException(404) */
    fun listSubfolders(url: HttpUrl): List<String> {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "1")
            .build()
        return call(request).use { response ->
            if (response.code != 207) throw httpError(response, "フォルダ一覧の取得")
            parseSubfolders(response.body!!.string(), url)
        }
    }

    fun createFolder(url: HttpUrl) = mkcol(url)

    private fun mkcol(url: HttpUrl) {
        call(Request.Builder().url(url).method("MKCOL", null).build()).use { response ->
            // 405 は既に存在する場合
            if (response.code != 201 && response.code != 405) throw httpError(response, "フォルダの作成")
        }
    }

    fun put(url: HttpUrl, body: RequestBody, mtimeSeconds: Long) {
        val request = Request.Builder()
            .url(url)
            .put(body)
            .header("X-OC-Mtime", mtimeSeconds.toString())
            .build()
        call(request).use { response ->
            if (!response.isSuccessful) throw httpError(response, "アップロード")
        }
    }

    fun supportsTus(davRoot: String): Boolean {
        val request = Request.Builder().url(davRoot).method("OPTIONS", null).build()
        return call(request).use { response ->
            response.isSuccessful &&
                response.header("Tus-Version").orEmpty().contains(TUS_VERSION) &&
                response.header("Tus-Extension").orEmpty().contains("creation")
        }
    }

    /** TUS アップロードを作成し、アップロード先 URL を返す */
    fun tusCreate(collectionUrl: HttpUrl, fileName: String, length: Long, mtimeSeconds: Long): String {
        val request = Request.Builder()
            .url(collectionUrl)
            .post(ByteArray(0).toRequestBody(null))
            .header("Tus-Resumable", TUS_VERSION)
            .header("Upload-Length", length.toString())
            .header(
                "Upload-Metadata",
                TusMetadata.encode(mapOf("filename" to fileName, "mtime" to mtimeSeconds.toString())),
            )
            .build()
        return call(request).use { response ->
            if (response.code != 201) throw httpError(response, "分割アップロードの開始")
            val location = response.header("Location")
                ?: throw RemoteHttpException(response.code, "分割アップロードの開始: Location ヘッダーがありません")
            collectionUrl.resolve(location)?.toString()
                ?: throw RemoteHttpException(response.code, "分割アップロードの開始: 不正な Location: $location")
        }
    }

    /** アップロード済みのオフセット。アップロードが失効していれば null */
    fun tusOffset(uploadUrl: String): Long? {
        val request = Request.Builder()
            .url(uploadUrl)
            .head()
            .header("Tus-Resumable", TUS_VERSION)
            .build()
        return call(request).use { response ->
            when {
                response.code == 404 || response.code == 410 -> null
                response.isSuccessful -> response.header("Upload-Offset")?.toLongOrNull()
                else -> throw httpError(response, "分割アップロードの状態確認")
            }
        }
    }

    /** チャンクを送信し、送信後のオフセットを返す */
    fun tusPatch(uploadUrl: String, offset: Long, body: RequestBody): Long {
        val request = Request.Builder()
            .url(uploadUrl)
            .patch(body)
            .header("Tus-Resumable", TUS_VERSION)
            .header("Upload-Offset", offset.toString())
            .build()
        return call(request).use { response ->
            if (!response.isSuccessful) throw httpError(response, "分割アップロード")
            response.header("Upload-Offset")?.toLongOrNull()
                ?: throw RemoteHttpException(response.code, "分割アップロード: Upload-Offset ヘッダーがありません")
        }
    }

    private fun call(request: Request): Response {
        val response = http.newCall(request).execute()
        if (response.code == 401) {
            response.close()
            throw AuthRequiredException("サーバーに認証を拒否されました。公式アプリで再ログインしてください")
        }
        return response
    }

    private fun httpError(response: Response, action: String): RemoteHttpException {
        val detail = runCatching { response.body?.string()?.take(200) }.getOrNull().orEmpty()
        return RemoteHttpException(response.code, "$action に失敗しました (HTTP ${response.code}) $detail".trim())
    }

    private fun parsePropfind(xml: String): RemoteEntry {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        var isCollection = false
        var size = 0L
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.namespace != "DAV:") continue
            when (parser.name) {
                "collection" -> isCollection = true
                "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
            }
        }
        return RemoteEntry(isCollection, size)
    }

    private fun parseSubfolders(xml: String, self: HttpUrl): List<String> {
        val selfPath = Uri.decode(self.encodedPath).trimEnd('/')
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        val names = ArrayList<String>()
        var href: String? = null
        var isCollection = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.namespace != "DAV:") continue
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        href = null
                        isCollection = false
                    }
                    "href" -> href = parser.nextText().trim()
                    "collection" -> isCollection = true
                }
                XmlPullParser.END_TAG -> if (parser.name == "response" && isCollection) {
                    // href は絶対URLの場合とパスのみの場合がある
                    val encodedPath = href?.let { it.toHttpUrlOrNull()?.encodedPath ?: it } ?: continue
                    val path = Uri.decode(encodedPath).trimEnd('/')
                    if (path != selfPath) names += path.substringAfterLast('/')
                }
            }
        }
        return names
    }

    /**
     * Bearer トークンを付与する。401 の場合は (期限切れのキャッシュとみなして) トークンを失効させ、
     * 公式アプリの Authenticator にリフレッシュさせてから 1 回だけ再送する。
     */
    private class BearerAuthInterceptor(private val tokens: TokenSource) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = tokens.token()
            val response = chain.proceed(chain.request().withBearer(token))
            if (response.code != 401) return response
            response.close()
            tokens.invalidate(token)
            return chain.proceed(chain.request().withBearer(tokens.token()))
        }

        private fun Request.withBearer(token: String) =
            newBuilder().header("Authorization", "Bearer $token").build()
    }

    companion object {
        private const val TUS_VERSION = "1.0.0"
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}
