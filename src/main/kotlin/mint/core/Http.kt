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

    /** Запрос без таймаута может висеть вечно; у загрузки файла свой, более длинный. */
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    /**
     * Клиент один на весь лаунчер, и это обязывает: брошенный обмен ломает его целиком.
     * 03.10 в портативной сборке семь рабочих потоков клиента крутились в `SSLEngine.unwrap`
     * и ели 3,4 ядра **после** того, как игра и сервер были закрыты, — по 43 минуты процессорного
     * времени на поток. Причина — тело ответа (`ofInputStream`), оставшееся недочитанным,
     * когда параллельная загрузка сборки оборвалась. Отсюда правила ниже: тело всегда
     * дочитывается или закрывается, у каждого запроса есть таймаут.
     */
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * GET с повторами: один таймаут соединения не должен ронять запуск игры.
     * Повторять GET безопасно — он ничего не меняет на той стороне.
     */
    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", USER_AGENT)
            .timeout(REQUEST_TIMEOUT)
            .GET().build()
        var last: Exception? = null
        repeat(3) { attempt ->
            ensureActive()
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}: $url")
                return@withContext response.body()
            } catch (e: Exception) {
                last = e
                if (attempt < 2) Thread.sleep(700L * (attempt + 1))
            }
        }
        throw IOException("Не удалось получить $url: ${last?.message}", last)
    }

    suspend fun getJson(url: String): JsonElement = MintJson.parseToJsonElement(getString(url))

    suspend fun postJson(url: String, body: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
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

    suspend fun download(task: DownloadTask, onBytes: ((Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
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

    private fun downloadOnce(task: DownloadTask, onBytes: ((Long) -> Unit)?) {
        task.target.parentFile.mkdirs()
        val tmp = File(task.target.path + ".part")
        val request = HttpRequest.newBuilder(URI(task.url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofMinutes(5))
            .GET().build()

        if (onBytes == null) {
            // Тело забирает сам java.net.http: он дочитывает его до конца и закрывает соединение
            // при любой ошибке. Поток наружу не отдаём — незакрытый поток и есть тот случай,
            // из-за которого клиент потом крутится вхолостую (см. комментарий к [client]).
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
            if (response.statusCode() !in 200..299) {
                tmp.delete()
                throw IOException("HTTP ${response.statusCode()}")
            }
        } else {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val body = response.body()
            if (response.statusCode() !in 200..299) {
                drainAndClose(body)
                throw IOException("HTTP ${response.statusCode()}")
            }
            try {
                body.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                }
            } catch (e: Throwable) {
                // Прерванная загрузка: тело обязано быть закрыто, иначе обмен останется висеть
                drainAndClose(body)
                tmp.delete()
                throw e
            }
        }

        if (task.sha1 != null && !sha1(tmp).equals(task.sha1, ignoreCase = true)) {
            tmp.delete()
            throw IOException("Хеш не совпал для ${task.target.name}")
        }
        if (task.target.exists()) task.target.delete()
        if (!tmp.renameTo(task.target)) throw IOException("Не удалось сохранить ${task.target}")
    }

    /** Закрыть тело ответа так, чтобы обмен точно завершился, что бы ни случилось выше. */
    private fun drainAndClose(body: java.io.InputStream) {
        runCatching { body.close() }
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
