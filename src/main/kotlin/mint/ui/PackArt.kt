package mint.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mint.game.Instance
import java.io.File
import java.io.InputStream

/**
 * Картинки сборок. Официальные вшиты в лаунчер (resources/packs/<id>/), поэтому видны
 * ещё до скачивания сборки. У своих локальных сборок — icon.png / banner.png в папке сборки.
 */
object PackArt {
    private val cache = HashMap<String, ImageBitmap?>()

    fun banner(instance: Instance): ImageBitmap? =
        resource("packs/${instance.id}/banner.jpg") ?: file(File(instance.dir, "banner.png"))

    fun icon(instance: Instance): ImageBitmap? =
        resource("packs/${instance.id}/icon.png") ?: file(File(instance.dir, "icon.png"))

    private fun resource(path: String): ImageBitmap? = cache.getOrPut("res:$path") {
        PackArt::class.java.classLoader.getResourceAsStream(path)?.let(::decode)
    }

    /** Файл с диска могли заменить — ключ кэша учитывает время изменения. */
    private fun file(file: File): ImageBitmap? {
        if (!file.isFile) return null
        return cache.getOrPut("file:${file.path}:${file.lastModified()}") { decode(file.inputStream()) }
    }

    private fun decode(input: InputStream): ImageBitmap? =
        runCatching { input.buffered().use { loadImageBitmap(it) } }.getOrNull()
}

/** Иконка сборки; без картинки — первая буква названия на мятной плитке. */
@Composable
fun PackIcon(instance: Instance, size: Dp, radius: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(radius)
    val icon = PackArt.icon(instance)
    Box(
        modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(MintColors.HeroTop, MintColors.Mint)))
            .border(1.dp, MintColors.ink(0.08f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Txt(instance.name.take(1).uppercase(), nunito(size.value * 0.5f, color = MintColors.MintInk))
        }
    }
}
