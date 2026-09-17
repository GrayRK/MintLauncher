package mint.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mint.core.DownloadTask
import mint.core.Http
import mint.core.MintPaths
import mint.core.toHex
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

data class JavaInfo(val executable: File, val version: String, val vendor: String) {
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
    val label get() = "Java $version${if (vendor.isNotBlank()) " ($vendor)" else ""}"
}

object JavaRuntime {
    private const val MAJOR = 21

    val managedHome get() = File(MintPaths.runtime, "java-$MAJOR")
    val managedJavaw get() = File(managedHome, "bin/javaw.exe")

    /** Информация о Java по пути к java(w).exe — читаем файл release из JAVA_HOME. */
    fun inspect(executable: File): JavaInfo? {
        if (!executable.isFile) return null
        val home = executable.parentFile?.parentFile ?: return null
        val release = File(home, "release")
        if (!release.isFile) return JavaInfo(executable, "?", "")
        val props = release.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i < 0) null else line.substring(0, i) to line.substring(i + 1).trim('"')
        }.toMap()
        val vendor = when (val impl = props["IMPLEMENTOR"].orEmpty()) {
            "Eclipse Adoptium" -> "Temurin"
            else -> impl
        }
        return JavaInfo(executable, props["JAVA_VERSION"] ?: "?", vendor)
    }

    fun managed(): JavaInfo? = inspect(managedJavaw)

    /** Скачивает Temurin JRE 21 в runtime/java-21, если его ещё нет. */
    suspend fun ensureManaged(progress: ProgressSink): JavaInfo {
        managed()?.let { if (it.major == MAJOR) return it }
        progress.report("Поиск Java $MAJOR", null)
        val assets = Http.getJson(
            "https://api.adoptium.net/v3/assets/latest/$MAJOR/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse"
        ).jsonArray
        val pkg = assets.first().jsonObject.obj("binary").obj("package")
        val link = pkg.str("link")!!
        val sha256 = pkg.str("checksum")
        val size = pkg.long("size") ?: -1

        val zip = File(MintPaths.cache, "java-$MAJOR.zip")
        var received = 0L
        Http.download(DownloadTask(link, zip, size = size)) { n ->
            received += n
            if (size > 0) progress.report("Загрузка Java $MAJOR", received.toFloat() / size)
        }
        progress.report("Распаковка Java $MAJOR", null)
        withContext(Dispatchers.IO) {
            if (sha256 != null && !sha256File(zip).equals(sha256, ignoreCase = true)) {
                zip.delete()
                throw IOException("Контрольная сумма Java не совпала")
            }
            val tmp = File(MintPaths.runtime, "java-$MAJOR.tmp")
            tmp.deleteRecursively()
            unzip(zip, tmp)
            // В архиве один корневой каталог вида jdk-21.0.x+y-jre
            val root = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
            managedHome.deleteRecursively()
            if (!root.renameTo(managedHome)) throw IOException("Не удалось установить Java в $managedHome")
            tmp.deleteRecursively()
            zip.delete()
        }
        return managed() ?: throw IOException("Java не найдена после установки")
    }

    private fun kotlinx.serialization.json.JsonObject.obj(key: String) = this[key]!!.jsonObject

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }
}

fun unzip(zip: File, target: File) {
    val canonicalTarget = target.canonicalFile
    ZipFile(zip).use { zf ->
        for (entry in zf.entries()) {
            val out = File(canonicalTarget, entry.name).canonicalFile
            if (!out.path.startsWith(canonicalTarget.path)) continue // защита от zip-slip
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile.mkdirs()
                zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
