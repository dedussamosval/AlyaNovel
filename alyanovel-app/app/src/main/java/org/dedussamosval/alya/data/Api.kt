package org.dedussamosval.alya.data

import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.dedussamosval.alya.formats.JsonException
import org.dedussamosval.alya.formats.MiniJson
import org.dedussamosval.alya.formats.obj
import org.dedussamosval.alya.formats.string
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Сервер ответил ошибкой. Наследует IOException, чтобы «нет сети» и «ошибка сервера» обрабатывались одинаково. */
class ApiException(message: String, val code: Int = 0) : IOException(message)

/** Сессия недействительна (401) — нужно войти заново. */
class UnauthorizedException : IOException("Сессия устарела")

/**
 * Все методы блокирующие — вызывайте их из Dispatchers.IO.
 * Контракт эндпоинтов описан в docs/PROTOCOL.md.
 */
class Api(private val http: OkHttpClient, baseUrl: String) {

    val baseUrl: String = baseUrl.trimEnd('/')

    private fun requestJson(request: Request): String {
        http.newCall(request).execute().use { r ->
            if (r.code == 401) throw UnauthorizedException()
            if (!r.isSuccessful) throw ApiException(errorDetail(r) ?: "Сервер ответил ${r.code}", r.code)
            return r.body?.string() ?: throw ApiException("Пустой ответ сервера")
        }
    }

    private fun errorDetail(r: okhttp3.Response): String? =
        runCatching { MiniJson.parse(r.body?.string().orEmpty()).obj().string("detail") }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private fun get(path: String, session: String? = null): String {
        val b = Request.Builder().url(baseUrl + path)
        if (session != null) b.header("Authorization", "Bearer $session")
        return requestJson(b.build())
    }

    // ───── каталог ─────
    fun volumesJson(kind: String): String = get("/api/$kind/volumes")

    fun latest(since: Long): List<LatestChapter> = parseLatest(get("/api/latest?since=$since"))

    fun appVersion(): AppVersion {
        val o = parseObject(get("/api/app-version"))
        return AppVersion(o.string("latest"), o.string("url"))
    }

    /** Скачивает файл главы в dest (прогресс 0..1). Запись во временный файл — на стороне вызывающего. */
    fun downloadChapter(id: Int, dest: File, onProgress: (Float) -> Unit) {
        val client = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("$baseUrl/api/chapters/$id/file").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("Сервер ответил ${r.code}", r.code)
            val body = r.body ?: throw ApiException("Пустой ответ сервера")
            val total = body.contentLength()
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            }
        }
    }


    fun notices(after: Int, limit: Int = 5): NoticeBatch =
        parseNotices(get("/api/notices?after=$after&limit=$limit"))

    private fun parseObject(json: String): Map<String, Any?> = try {
        MiniJson.parse(json).obj()
    } catch (e: JsonException) {
        throw ApiException("Неожиданный ответ сервера")
    }
}
