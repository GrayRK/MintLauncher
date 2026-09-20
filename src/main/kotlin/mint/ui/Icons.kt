package mint.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Dp

/** Иконки Phosphor (regular), лежат в resources/icons. */
enum class MintIcon(val file: String) {
    Minus("minus"), Square("square"), X("x"),
    House("house"), Cube("cube"), Puzzle("puzzle-piece"), UserCircle("user-circle"), Gear("gear-six"),
    Play("play"), Eye("eye"), EyeSlash("eye-slash"), Check("check"),
    User("user"), GameController("game-controller"), Cpu("cpu"), PaintBrush("paint-brush"),
    Windows("windows-logo"), FolderOpen("folder-open"), Refresh("arrow-clockwise"), SignOut("sign-out"),
    Warning("warning-circle"), Rotate3d("rotate-3d"),
    Server("hard-drives"), Stop("stop"), Copy("copy"),
}

private val cache = HashMap<MintIcon, Painter>()

@Composable
fun Icon(icon: MintIcon, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val painter = remember(icon) {
        cache.getOrPut(icon) {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icons/${icon.file}.svg")
                ?: error("Нет иконки ${icon.file}")
            stream.use { loadSvgPainter(it, density) }
        }
    }
    Image(painter, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}
