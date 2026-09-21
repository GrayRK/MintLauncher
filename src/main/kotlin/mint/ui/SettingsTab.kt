package mint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mint.core.Theme
import mint.game.GameLauncher
import mint.game.JavaRuntime
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt

/** Вкладка «Настройки»: все разделы одной страницей с прокруткой. */
@Composable
fun SettingsTab(app: AppState) {
    val s = app.settings
    val maxGb = systemRamGb().coerceIn(4, 64)
    val recommended = if (maxGb >= 12) 6 else 4

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header("Настройки")

        Section("Память")
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Выделено памяти", manrope(14f, FontWeight.SemiBold))
                Spacer()
                Txt("${formatGb(s.memoryMb / 1024f)} ГБ", nunito(22f, color = MintColors.MintDeep))
            }
            MemorySlider(
                valueGb = s.memoryMb / 1024f, minGb = 2, maxGb = maxGb,
                onChange = { gb -> app.updateSettings { it.copy(memoryMb = gb * 1024) } },
            )
            Row {
                Txt("2 ГБ", manrope(11.5f, color = MintColors.ink(0.78f)))
                Spacer()
                Txt("рекомендуем $recommended ГБ", manrope(11.5f, color = MintColors.ink(0.78f)))
                Spacer()
                Txt("$maxGb ГБ", manrope(11.5f, color = MintColors.ink(0.78f)))
            }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Память локального сервера", manrope(14f, FontWeight.SemiBold))
                Spacer()
                Txt("${formatGb(s.serverMemoryMb / 1024f)} ГБ", nunito(22f, color = MintColors.MintDeep))
            }
            MemorySlider(
                valueGb = s.serverMemoryMb / 1024f, minGb = 2, maxGb = maxGb,
                onChange = { gb -> app.updateSettings { it.copy(serverMemoryMb = gb * 1024) } },
            )
            Txt(
                "Сервер и игра запускаются вместе, поэтому в сумме они не должны занимать всю память компьютера.",
                manrope(11.5f, color = MintColors.ink(0.78f)),
            )
        }

        Section("Java")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.weight(1f).height(130.dp), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), spacing = 11.dp) {
                Txt("Версия Java", manrope(13.5f, FontWeight.SemiBold))
                Segmented(
                    listOf("Автоматически", "Вручную"), if (s.javaAuto) 0 else 1,
                    onSelect = { i -> app.updateSettings { it.copy(javaAuto = i == 0) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                val info = app.javaInfo
                Txt(
                    when {
                        info != null -> "Найдена ${info.label}"
                        s.javaAuto -> "Java 21 скачается при первом запуске"
                        else -> "Укажите путь к javaw.exe (нужна Java 21)"
                    },
                    manrope(11.5f, FontWeight.Normal, if (info == null && !s.javaAuto) MintColors.Danger else MintColors.ink(0.78f)),
                )
            }
            Card(Modifier.weight(1f).height(130.dp), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), spacing = 11.dp) {
                Txt("Путь к Java", manrope(13.5f, FontWeight.SemiBold))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = if (s.javaAuto) JavaRuntime.managedJavaw.path else s.javaPath.ifBlank { "не выбран" }
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                            .background(MintColors.ink(0.04f))
                            .border(1.dp, MintColors.ink(0.11f), RoundedCornerShape(11.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Txt(shown, Mono.copy(fontSize = 11.5.sp, color = MintColors.ink(0.8f)), maxLines = 1)
                    }
                    OutlineButton("Обзор", height = 40.dp, radius = 11.dp, textStyle = manrope(12.5f, FontWeight.SemiBold)) {
                        pickJava()?.let { path -> app.updateSettings { it.copy(javaAuto = false, javaPath = path) } }
                    }
                }
            }
        }

        Section("Запуск")
        Card(padding = PaddingValues(horizontal = 22.dp, vertical = 6.dp), spacing = 0.dp) {
            ToggleRow("Закрывать лаунчер при запуске игры", "Лаунчер скроется и вернётся после выхода из игры", s.closeOnLaunch) { v ->
                app.updateSettings { it.copy(closeOnLaunch = v) }
            }
        }

        Section("Внешний вид")
        Card(spacing = 11.dp) {
            Txt("Тема", manrope(13.5f, FontWeight.SemiBold))
            val themes = listOf(Theme.LIGHT, Theme.DARK, Theme.SYSTEM)
            Segmented(
                listOf("Светлая", "Тёмная", "Как в системе"), themes.indexOf(s.theme).coerceAtLeast(0),
                onSelect = { i -> app.updateSettings { it.copy(theme = themes[i]) } },
                modifier = Modifier.width(420.dp),
            )
        }

        Section("О лаунчере")
        AboutLauncher(app)
    }
}

@Composable
private fun Section(title: String) {
    Txt(
        title.uppercase(), manrope(10.5f, FontWeight.SemiBold, MintColors.ink(0.7f), letterSpacing = 1.3.sp),
        Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
fun Header(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, nunito(24f, color = MintColors.InkStrong))
        if (subtitle != null) Txt(subtitle, manrope(13f, FontWeight.Normal, MintColors.ink(0.78f)))
    }
}

private fun pickJava(): String? {
    val dialog = FileDialog(null as Frame?, "Выберите javaw.exe", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.equals("javaw.exe", true) || name.equals("java.exe", true) }
    dialog.file = "javaw.exe"
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}

@Composable
private fun MemorySlider(valueGb: Float, minGb: Int, maxGb: Int, onChange: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        val trackWidth = maxWidth
        val fraction = ((valueGb - minGb) / (maxGb - minGb)).coerceIn(0f, 1f)
        fun set(x: Float) {
            val f = (x / widthPx).coerceIn(0f, 1f)
            onChange((minGb + f * (maxGb - minGb)).roundToInt())
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(minGb, maxGb, widthPx) { detectTapGestures { set(it.x) } }
                .pointerInput(minGb, maxGb, widthPx) { detectDragGestures { change, _ -> set(change.position.x) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            val track = RoundedCornerShape(6.dp)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(track).background(MintColors.ink(0.08f)))
            Box(Modifier.fillMaxWidth(fraction).height(10.dp).clip(track).background(MintColors.Mint))
            Box(
                Modifier.offset(x = trackWidth * fraction - 11.dp).size(22.dp)
                    .shadow(4.dp, CircleShape, spotColor = MintColors.MintDeep)
                    .clip(CircleShape).background(MintColors.Knob)
                    .border(2.dp, MintColors.MintBorder, CircleShape)
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, divider: Boolean = false, onChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(title, manrope(13.5f, FontWeight.SemiBold))
                Txt(subtitle, manrope(11.5f, FontWeight.Normal, MintColors.ink(0.78f)))
            }
            Toggle(checked, onChange)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(MintColors.ink(0.06f)))
    }
}
