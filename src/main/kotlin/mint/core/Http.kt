package mint.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

val MintJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

data class DownloadTask(
    val url: String,
    val target: File,
    val sha1: String? = null,
    val size: Long = -1,
)

object Http {
    private const val USER_AGENT = "Mint-Launcher/0.1"

    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", USER_AGENT).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}: $url")
        response.body()
    }

    suspend fun getJson(url: String): JsonElement = MintJson.parseToJsonElement(getString(url))

    suspend fun postJson(url: String, body: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() to response.body()
    }

    /** Файл уже на месте и совпадает по хешу/размеру. */
    fun isValid(task: DownloadTask): Boolean {
        val f = task.target
        if (!f.isFile) return false
        if (task.size >= 0 && f.length() != task.size) return false
        if (task.sha1 != null) return sha1(f).equals(task.sha1, ignoreCase = true)
        return true
    }

    suspend fun download(task: DownloadTask, onBytes: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (isValid(task)) return@withContext
        var lastError: Exception? = null
        repeat(3) { attempt ->
            ensureActive()
            try {
                downloadOnce(task, onBytes)
                return@withContext
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw IOException("Не удалось скачать ${task.url}: ${lastError?.message}", lastError)
    }

    private fun downloadOnce(task: DownloadTask, onBytes: (Long) -> Unit) {
        task.target.parentFile.mkdirs()
        val tmp = File(task.target.path + ".part")
        val request = HttpRequest.newBuilder(URI(task.url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofMinutes(5))
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw IOException("HTTP ${response.statusCode()}")
        }
        val digest = MessageDigest.getInstance("SHA-1")
        response.body().use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
        }
        if (task.sha1 != null) {
            val actual = digest.digest().toHex()
            if (!actual.equals(task.sha1, ignoreCase = true)) {
                tmp.delete()
                throw IOException("Хеш не совпал для ${task.target.name}")
            }
        }
        if (task.target.exists()) task.target.delete()
        if (!tmp.renameTo(task.target)) throw IOException("Не удалось сохранить ${task.target}")
    }

    /** Параллельная загрузка с общим прогрессом (0..1). */
    suspend fun downloadAll(
        tasks: List<DownloadTask>,
        parallelism: Int = 8,
        onProgress: (done: Int, total: Int) -> Unit,
    ) = coroutineScope {
        val pending = tasks.distinctBy { it.target.absolutePath }.filterNot { isValid(it) }
        val total = pending.size
        val done = AtomicLong(0)
        onProgress(0, total)
        val semaphore = Semaphore(parallelism)
        pending.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit { download(task) }
                onProgress(done.incrementAndGet().toInt(), total)
            }
        }.awaitAll()
    }
}

fun sha1(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
