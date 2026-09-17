package mint.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Палитра из референса Claude Design (светлая пастельная тема) и мятная тёмная.
 * [dark] — состояние Compose, поэтому смена темы сразу перерисовывает всё, что читает цвета.
 */
object MintColors {
    var dark by mutableStateOf(false)

    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val Page get() = pick(0xFFEFE9DE, 0xFF111513)
    val Window get() = pick(0xFFF6F1E7, 0xFF161A18)
    val Surface get() = pick(0xFFFBF7F0, 0xFF1D2220)
    val Sand get() = pick(0xFFE8E1D4, 0xFF1A1F1C)
    val SandDark get() = pick(0xFFD8CFC0, 0xFF2B322E)

    val Mint get() = pick(0xFFA8DCC6, 0xFF7CC4A8)
    val MintHover get() = pick(0xFF9AD5BC, 0xFF8CCFB5)
    val MintBorder get() = pick(0xFF8FCFB4, 0xFF69B395)
    val MintFocus get() = pick(0xFF7FCBAE, 0xFF7CC4A8)
    /** Мятный акцент для текста и иконок на фоне окна. */
    val MintDeep get() = pick(0xFF2F6A56, 0xFF8FD3B8)
    /** Иконки на мятной заливке. */
    val MintDarker get() = pick(0xFF1F5245, 0xFF143528)
    /** Текст на мятной заливке. */
    val MintInk get() = pick(0xFF17332B, 0xFF0F261E)
    val LinkHover get() = pick(0xFF1F5245, 0xFFB8E8D4)

    val HeroTop get() = pick(0xFFDCEEE4, 0xFF1E2E27)
    val HeroBottom get() = pick(0xFFEFE7D8, 0xFF1B201D)

    val Ink get() = pick(0xFF3A3A34, 0xFFE2E6E1)
    val InkStrong get() = pick(0xFF2C2C27, 0xFFF0F3EE)
    val Danger get() = pick(0xFFB4553F, 0xFFE58F78)
    val CloseHover get() = pick(0xFFE8C9BF, 0xFF5B2F27)
    /** Бегунки переключателей и слайдера. */
    val Knob get() = pick(0xFFFBF7F0, 0xFFE9EDE8)

    fun ink(alpha: Float) = Ink.copy(alpha = alpha)
}

object MintFonts {
    val Nunito = FontFamily(
        Font("fonts/Nunito-400.ttf", FontWeight.Normal),
        Font("fonts/Nunito-500.ttf", FontWeight.Medium),
        Font("fonts/Nunito-600.ttf", FontWeight.SemiBold),
        Font("fonts/Nunito-700.ttf", FontWeight.Bold),
    )
    val Manrope = FontFamily(
        Font("fonts/Manrope-400.ttf", FontWeight.Normal),
        Font("fonts/Manrope-500.ttf", FontWeight.Medium),
        Font("fonts/Manrope-600.ttf", FontWeight.SemiBold),
        Font("fonts/Manrope-700.ttf", FontWeight.Bold),
    )
}

fun nunito(size: Float, weight: FontWeight = FontWeight.Bold, color: Color = MintColors.Ink, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = MintFonts.Nunito, fontSize = size.sp, fontWeight = weight, color = color, lineHeight = lineHeight)

fun manrope(size: Float, weight: FontWeight = FontWeight.Medium, color: Color = MintColors.Ink, lineHeight: TextUnit = TextUnit.Unspecified, letterSpacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = MintFonts.Manrope, fontSize = size.sp, fontWeight = weight, color = color, lineHeight = lineHeight, letterSpacing = letterSpacing)

val Mono = TextStyle(fontFamily = FontFamily.Monospace)
