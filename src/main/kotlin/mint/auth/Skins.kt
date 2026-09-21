package mint.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mint.core.Account
import mint.core.AccountType
import mint.core.DownloadTask
import mint.core.Http
import mint.core.MintJson
import mint.core.MintPaths
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/** Текстура скина 64×64 (или устаревшая 64×32) и модель рук. */
class Skin(val image: BufferedImage, val slim: Boolean) {
    val legacy get() = image.height == image.width / 2
}

object Skins {
    private val dir get() = File(MintPaths.cache, "skins")

    /**
     * Yggdrasil: сервер сессий authlib-injector → свойство textures (base64 JSON) → URL скина и модель.
     * При недоступности сети берём последний сохранённый скин. Офлайн — стандартный Стив из клиента игры.
     */
    suspend fun load(account: Account, minecraft: String): Skin? = withContext(Dispatchers.IO) {
        when (account.type) {
            AccountType.OFFLINE -> defaultSkin(minecraft)
            AccountType.YGGDRASIL -> runCatching { fetch(account.authServer, account.uuid) }
                .onFailure { System.err.println("[mint] скин не загружен: ${it.message}") }
                .getOrNull() ?: cached(account.uuid) ?: defaultSkin(minecraft)
        }
    }

    /**
     * Скин другого игрока — для списка на вкладке сервера. [authServer] — сервер входа,
     * через который сервер пускает игроков; null — сервер офлайн, скинов у игроков нет.
     */
    suspend fun player(uuid: String, authServer: String?, minecraft: String): Skin? = withContext(Dispatchers.IO) {
        // Сервер пишет UUID с дефисами, Yggdrasil отдаёт без — кэш у них общий
        val id = uuid.replace("-", "").lowercase()
        if (authServer == null) return@withContext defaultSkin(minecraft)
        runCatching { fetch(authServer, id) }.getOrNull() ?: cached(id) ?: defaultSkin(minecraft)
    }

    private fun sessionBase(server: String): String = when (server.trim().lowercase()) {
        "ely.by", "" -> "https://authserver.ely.by/api/authlib-injector/sessionserver"
        else -> server.trim().trimEnd('/') + "/sessionserver"
    }

    private suspend fun fetch(authServer: String, uuid: String): Skin? {
        val profile = Http.getJson("${sessionBase(authServer)}/session/minecraft/profile/$uuid").jsonObject
        val encoded = profile["properties"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "textures" }
            ?.get("value")?.jsonPrimitive?.content
            ?: return null
        val textures = MintJson.parseToJsonElement(String(Base64.getDecoder().decode(encoded))).jsonObject
        val skin = textures["textures"]?.jsonObject?.get("SKIN")?.jsonObject ?: return null
        val url = skin["url"]!!.jsonPrimitive.content
        val slim = skin["metadata"]?.jsonObject?.get("model")?.jsonPrimitive?.content == "slim"

        val png = File(dir, "$uuid.png")
        png.delete()
        Http.download(DownloadTask(url, png))
        File(dir, "$uuid.model").writeText(if (slim) "slim" else "classic")
        return read(png, slim)
    }

    private fun cached(uuid: String): Skin? {
        val png = File(dir, "$uuid.png")
        if (!png.isFile) return null
        val slim = File(dir, "$uuid.model").takeIf { it.isFile }?.readText() == "slim"
        return read(png, slim)
    }

    private fun defaultSkin(minecraft: String): Skin? = runCatching {
        val jar = File(MintPaths.versions, "$minecraft/$minecraft.jar")
        if (!jar.isFile) return null
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("assets/minecraft/textures/entity/player/wide/steve.png") ?: return null
            zip.getInputStream(entry).use { ImageIO.read(it) }?.let { Skin(it.toArgb(), slim = false) }
        }
    }.getOrNull()

    private fun read(file: File, slim: Boolean): Skin? =
        runCatching { ImageIO.read(file) }.getOrNull()
            ?.takeIf { it.width >= 64 && (it.height == it.width || it.height == it.width / 2) }
            ?.let { Skin(it.toArgb(), slim) }

    private fun BufferedImage.toArgb(): BufferedImage {
        if (type == BufferedImage.TYPE_INT_ARGB) return this
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(this@toArgb, 0, 0, null); dispose() } }
    }
}
