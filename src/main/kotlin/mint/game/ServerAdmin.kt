package mint.game

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mint.core.MintJson
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Игрок сервера: кто когда-либо заходил или числится в списках (админы, белый список, баны). */
data class ServerPlayer(
    val name: String,
    /** UUID с дефисами, как его пишет сервер. */
    val uuid: String,
    val op: Boolean,
    val whitelisted: Boolean,
    val banned: Boolean,
)

/**
 * Обслуживание локального сервера, пока он выключен: списки игроков, server.properties,
 * мир и резервные копии.
 *
 * Работающий сервер держит списки и настройки в памяти и перезаписывает файлы сам,
 * поэтому, пока он запущен, всё меняется только командами в консоли (это делает [mint.ui.AppState]).
 */
object ServerAdmin {
    /** Копии лежат рядом с папкой сервера: «Удалить сервер» их не трогает. */
    fun backupsDir(instance: Instance) = File(instance.dir, "server-backups")

    // ---- server.properties ----

    private fun propertiesFile(instance: Instance) = File(ServerLauncher.dir(instance), "server.properties")

    /** Сервер пишет файл в UTF-8 и экранирует «:» — читаем стандартным парсером. */
    fun properties(instance: Instance): Map<String, String> {
        val file = propertiesFile(instance)
        if (!file.isFile) return emptyMap()
        val props = Properties()
        runCatching { file.reader(Charsets.UTF_8).use { props.load(it) } }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    /**
     * Меняет ключи построчно: порядок, комментарии и остальные значения игрока остаются как были.
     * Файла ещё нет — значит сервер не ставился, и его создаст [ServerLauncher.prepare].
     */
    fun setProperties(instance: Instance, values: Map<String, String>) {
        val file = propertiesFile(instance)
        if (!file.isFile) throw IOException("Настройки появятся после первого запуска сервера")
        setProperties(file, values)
    }

    internal fun setProperties(file: File, values: Map<String, String>) {
        val seen = mutableSetOf<String>()
        val lines = file.readLines(Charsets.UTF_8).map { line ->
            val key = line.substringBefore('=').trim()
            if (line.trimStart().startsWith("#") || key !in values) return@map line
            seen += key
            "$key=${escape(values.getValue(key))}"
        }
        val missing = values.filterKeys { it !in seen }.map { (k, v) -> "$k=${escape(v)}" }
        file.writeText((lines + missing).joinToString("\n") + "\n", Charsets.UTF_8)
    }

    /** Перевод строки сломал бы файл, а обратная косая черта — само значение. */
    private fun escape(value: String) =
        value.replace("\r", "").replace("\n", " ").replace("\\", "\\\\")

    // ---- Игроки ----

    private fun list(instance: Instance, name: String): List<JsonObject> {
        val file = File(ServerLauncher.dir(instance), name)
        if (!file.isFile) return emptyList()
        return runCatching { MintJson.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject } }
            .getOrElse { emptyList() }
    }

    private fun writeList(instance: Instance, name: String, entries: List<JsonObject>) {
        File(ServerLauncher.dir(instance), name).writeText(MintJson.encodeToString(JsonArray.serializer(), JsonArray(entries)))
    }

    private val JsonObject.uuid get() = this["uuid"]?.jsonPrimitive?.content.orEmpty().lowercase()
    private val JsonObject.name get() = this["name"]?.jsonPrimitive?.content.orEmpty()

    /**
     * Все, кого знает сервер. usercache.json помнит каждого, кто заходил;
     * списки добавляют тех, кого внесли командой, но кто ещё не появлялся.
     */
    fun players(instance: Instance): List<ServerPlayer> {
        val ops = list(instance, "ops.json").map { it.uuid }.toSet()
        val whitelist = list(instance, "whitelist.json").map { it.uuid }.toSet()
        val banned = list(instance, "banned-players.json").map { it.uuid }.toSet()

        val names = linkedMapOf<String, String>()
        for (file in listOf("usercache.json", "ops.json", "whitelist.json", "banned-players.json")) {
            list(instance, file).forEach { entry ->
                if (entry.uuid.isNotEmpty() && entry.name.isNotEmpty()) names.putIfAbsent(entry.uuid, entry.name)
            }
        }
        return names.map { (uuid, name) ->
            ServerPlayer(name, uuid, op = uuid in ops, whitelisted = uuid in whitelist, banned = uuid in banned)
        }.sortedBy { it.name.lowercase() }
    }

    fun setOp(instance: Instance, player: ServerPlayer, op: Boolean) = edit(instance, "ops.json", player, op) {
        mapOf(
            "uuid" to JsonPrimitive(player.uuid),
            "name" to JsonPrimitive(player.name),
            // 4 — полный доступ, как у op из консоли
            "level" to JsonPrimitive(4),
            "bypassesPlayerLimit" to JsonPrimitive(false),
        )
    }

    fun setWhitelisted(instance: Instance, player: ServerPlayer, on: Boolean) = edit(instance, "whitelist.json", player, on) {
        mapOf("uuid" to JsonPrimitive(player.uuid), "name" to JsonPrimitive(player.name))
    }

    fun setBanned(instance: Instance, player: ServerPlayer, banned: Boolean) = edit(instance, "banned-players.json", player, banned) {
        mapOf(
            "uuid" to JsonPrimitive(player.uuid),
            "name" to JsonPrimitive(player.name),
            // Формат даты тот же, что пишет сам сервер: иначе он не прочитает запись
            "created" to JsonPrimitive(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z"))),
            "source" to JsonPrimitive("Server"),
            "expires" to JsonPrimitive("forever"),
            "reason" to JsonPrimitive("Banned by an operator."),
        )
    }

    private fun edit(instance: Instance, file: String, player: ServerPlayer, present: Boolean, entry: () -> Map<String, JsonPrimitive>) {
        val rest = list(instance, file).filter { it.uuid != player.uuid.lowercase() }
        writeList(instance, file, if (present) rest + JsonObject(entry()) else rest)
    }

    // ---- Мир ----

    fun worldDir(instance: Instance): File {
        val root = ServerLauncher.dir(instance)
        val name = properties(instance)["level-name"]?.trim()?.ifBlank { null } ?: "world"
        return File(root, name)
    }

    /** Размер папки в байтах; мир с LOD Distant Horizons легко весит гигабайты. */
    fun sizeOf(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Резервная копия мира в server-backups/<мир>-<дата>.zip. Сжатие слабое:
     * регионы уже сжаты, а ждать минуту ради пары процентов незачем.
     */
    fun backupWorld(instance: Instance): File {
        val world = worldDir(instance)
        if (!world.isDirectory) throw IOException("Мира ещё нет — он создаётся при первом запуске сервера")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val target = File(backupsDir(instance).apply { mkdirs() }, "${world.name}-$stamp.zip")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                world.walkTopDown().filter { it.isFile && it.name != "session.lock" }.forEach { file ->
                    zip.putNextEntry(ZipEntry("${world.name}/${file.relativeTo(world).invariantSeparatorsPath}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return target
    }

    /**
     * Удаляет мир (вместе с Незером, Эндом и LOD). Новый создастся при следующем запуске —
     * уже с пресетом генерации сборки. Пустой [seed] — случайный: старый сид в server.properties
     * иначе вернул бы тот же самый мир.
     */
    fun resetWorld(instance: Instance, seed: String) {
        val world = worldDir(instance)
        if (world.exists() && !world.deleteRecursively()) {
            throw IOException("Не удалось удалить ${world.name}: файлы заняты. Сервер точно остановлен?")
        }
        if (propertiesFile(instance).isFile) setProperties(instance, mapOf("level-seed" to seed.trim()))
    }

    /**
     * Стирает папку сервера целиком: NeoForge, мир, конфиги, списки игроков.
     * Следующий запуск поставит сервер с нуля. Резервные копии лежат снаружи и остаются.
     */
    fun deleteServer(instance: Instance) {
        val root = ServerLauncher.dir(instance)
        if (root.exists() && !root.deleteRecursively()) {
            throw IOException("Часть файлов сервера занята и не удалилась. Закройте программы, открывшие папку server, и повторите")
        }
    }
}
