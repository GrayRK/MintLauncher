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
            AccountType.YGGDRASIL -> runCatching { fetch(account) }
                .onFailure { System.err.println("[mint] скин не загружен: ${it.message}") }
                .getOrNull() ?: cached(account) ?: defaultSkin(minecraft)
        }
    }

    private fun sessionBase(server: String): String = when (server.trim().lowercase()) {
        "ely.by", "" -> "https://authserver.ely.by/api/authlib-injector/sessionserver"
        else -> server.trim().trimEnd('/') + "/sessionserver"
    }

    private suspend fun fetch(account: Account): Skin? {
        val profile = Http.getJson("${sessionBase(account.authServer)}/session/minecraft/profile/${account.uuid}").jsonObject
        val encoded = profile["properties"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "textures" }
            ?.get("value")?.jsonPrimitive?.content
            ?: return null
        val textures = MintJson.parseToJsonElement(String(Base64.getDecoder().decode(encoded))).jsonObject
        val skin = textures["textures"]?.jsonObject?.get("SKIN")?.jsonObject ?: return null
        val url = skin["url"]!!.jsonPrimitive.content
        val slim = skin["metadata"]?.jsonObject?.get("model")?.jsonPrimitive?.content == "slim"

        val png = File(dir, "${account.uuid}.png")
        png.delete()
        Http.download(DownloadTask(url, png))
        File(dir, "${account.uuid}.model").writeText(if (slim) "slim" else "classic")
        return read(png, slim)
    }

    private fun cached(account: Account): Skin? {
        val png = File(dir, "${account.uuid}.png")
        if (!png.isFile) return null
        val slim = File(dir, "${account.uuid}.model").takeIf { it.isFile }?.readText() == "slim"
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
