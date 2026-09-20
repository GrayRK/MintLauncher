package mint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.tan

/** Вкладка «Главная». */
@Composable
fun HomeTab(app: AppState) {
    Column(
        Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Hero(app, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(178.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            InstancesCard(app, Modifier.weight(1f).fillMaxHeight())
            NewsCard(Modifier.weight(1f).fillMaxHeight())
            ResourcesCard(app, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Hero(app: AppState, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val instance = app.selectedInstance
    Box(
        modifier.clip(shape)
            .background(Brush.linearGradient(listOf(MintColors.HeroTop, MintColors.HeroBottom), start = Offset(0f, 0f), end = Offset(600f, 1400f)))
            .border(1.dp, MintColors.ink(0.07f), shape)
    ) {
        DiagonalStripes(Modifier.fillMaxSize())

        val launch = app.launch
        if (app.settings.showConsole && app.consoleLines.isNotEmpty()) {
            Console(app, Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 130.dp).fillMaxHeight())
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 26.dp, end = 26.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Txt("ПРОДОЛЖИТЬ", manrope(10.5f, FontWeight.SemiBold, MintColors.MintDeep, letterSpacing = 1.4.sp))
                Txt(instance.name, nunito(30f, color = MintColors.InkStrong, lineHeight = 34.sp), maxLines = 1)
                val subtitle = instance.subtitle.let {
                    if (instance.loaderVersion.isNotBlank()) it.replace("NeoForge", "NeoForge ${instance.loaderVersion}") else it
                }
                Txt(subtitle, manrope(12.5f, FontWeight.Normal, MintColors.ink(0.78f)))
                when (launch) {
                    is LaunchState.Preparing -> Column(Modifier.padding(top = 10.dp).widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Txt(launch.stage, manrope(11.5f, color = MintColors.ink(0.85f)), maxLines = 1)
                        ProgressBar(launch.fraction, Modifier.fillMaxWidth())
                    }
                    is LaunchState.Failed -> Row(
                        Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(MintIcon.Warning, 14.dp, MintColors.Danger)
                        Txt(launch.message, manrope(11.5f, color = MintColors.Danger), maxLines = 3)
                    }
                    else -> {}
                }
                ServerStatus(app)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                ServerButton(app)
                when (launch) {
                    is LaunchState.Preparing -> OutlineButton("Отмена", height = 52.dp, radius = 15.dp) { app.cancelLaunch() }
                    is LaunchState.Running -> PrimaryButton(
                        "В игре", height = 52.dp, radius = 15.dp, enabled = false,
                        textStyle = nunito(18f, color = MintColors.MintInk),
                    ) {}
                    else -> PrimaryButton(
                        if (launch is LaunchState.Failed) "Повторить" else "Играть",
                        height = 52.dp, radius = 15.dp,
                        textStyle = nunito(18f, color = MintColors.MintInk),
                        leading = { Icon(if (launch is LaunchState.Failed) MintIcon.Refresh else MintIcon.Play, 18.dp, MintColors.MintDarker) },
                    ) { app.play() }
                }
            }
        }
    }
}

/** Кнопка локального сервера: поднять сборку для друзей и остановить её. */
@Composable
private fun ServerButton(app: AppState) {
    when (val state = app.server) {
        is ServerState.Preparing -> OutlineButton(
            "Отмена сервера", height = 52.dp, radius = 15.dp,
        ) { app.toggleServer() }
        is ServerState.Running -> OutlineButton(
            "Остановить", height = 52.dp, radius = 15.dp,
            leading = { Icon(MintIcon.Stop, 16.dp, MintColors.ink(0.7f)) },
        ) { app.toggleServer() }
        is ServerState.Stopping -> OutlineButton(
            "Остановка…", height = 52.dp, radius = 15.dp, enabled = false,
        ) {}
        else -> OutlineButton(
            "Сервер", height = 52.dp, radius = 15.dp,
            leading = { Icon(MintIcon.Server, 16.dp, MintColors.ink(0.7f)) },
        ) { app.toggleServer() }
    }
}

/** Ход запуска сервера, его адрес и согласие с EULA. */
@Composable
private fun ServerStatus(app: AppState) {
    when (val state = app.server) {
        is ServerState.Preparing -> Column(
            Modifier.padding(top = 10.dp).widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Txt(state.stage, manrope(11.5f, color = MintColors.ink(0.85f)), maxLines = 1)
            ProgressBar(state.fraction, Modifier.fillMaxWidth())
        }

        is ServerState.Running -> Row(
            Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MintIcon.Server, 14.dp, MintColors.MintDeep)
            val hub = mint.game.ServerLauncher.TUNNEL_HUB
            val label = when {
                !state.ready -> "Сервер загружается…"
                // Туннель не выдаёт свой адрес: друзья заходят на хаб и выбирают сервер по названию
                state.tunnel -> "Сервер работает · друзьям: $hub, там выбрать «${app.selectedInstance.name}»"
                else -> "Сервер работает · localhost:${mint.game.ServerLauncher.DEFAULT_PORT} · туннель не поднялся"
            }
            Txt(label, manrope(11.5f, color = MintColors.ink(0.85f)), maxLines = 1)
            if (state.ready) {
                val copied = if (state.tunnel) hub else "localhost:${mint.game.ServerLauncher.DEFAULT_PORT}"
                LinkText("копировать") { copyToClipboard(copied) }
            }
        }

        is ServerState.Stopping -> Txt(
            "Сервер сохраняет мир и выключается…",
            manrope(11.5f, color = MintColors.ink(0.85f)),
            Modifier.padding(top = 10.dp),
        )

        is ServerState.Failed -> Column(
            Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(MintIcon.Warning, 14.dp, MintColors.Danger)
                Txt(state.message, manrope(11.5f, color = MintColors.Danger), maxLines = 3)
            }
            if (!app.settings.eulaAccepted) {
                LinkText("Принимаю EULA Minecraft и запускаю сервер") {
                    app.acceptEula()
                    app.toggleServer()
                }
            }
        }

        ServerState.Idle -> {}
    }
}

private fun copyToClipboard(text: String) = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .setContents(java.awt.datatransfer.StringSelection(text), null)
}

@Composable
private fun DiagonalStripes(modifier: Modifier) {
    val color = MintColors.ink(0.055f)
    Canvas(modifier) {
        // repeating-linear-gradient(115deg, ... 1px, transparent 13px): линии перпендикулярны направлению 115°
        val step = 13.dp.toPx()
        val angle = 25.0 * PI / 180 // наклон линий от вертикали
        val dx = (size.height * tan(angle)).toFloat()
        val spacing = (step / kotlin.math.cos(angle)).toFloat()
        var x = -dx
        while (x < size.width + dx) {
            drawLine(color, Offset(x + dx, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            x += spacing
        }
    }
}

@Composable
private fun Console(app: AppState, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(app.consoleLines.size) {
        if (app.consoleLines.isNotEmpty()) state.scrollToItem(app.consoleLines.size - 1)
    }
    LazyColumn(
        modifier.clip(RoundedCornerShape(12.dp)).background(MintColors.Surface.copy(alpha = 0.85f)),
        state = state,
        contentPadding = PaddingValues(12.dp),
    ) {
        items(app.consoleLines) { line ->
            Txt(line, Mono.copy(fontSize = 10.5.sp, color = MintColors.ink(0.8f)), maxLines = 2)
        }
    }
}

@Composable
private fun InstancesCard(app: AppState, modifier: Modifier) {
    Card(modifier, radius = 15.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 15.dp), spacing = 11.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Мои сборки", manrope(12.5f, FontWeight.SemiBold))
            Spacer()
            LinkText("папка", manrope(11f, color = MintColors.MintDeep)) { app.openInstanceFolder() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            app.instances.take(3).forEach { instance ->
                val selected = instance.id == app.selectedInstance.id
                val (source, hovered) = rememberHover()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (hovered) MintColors.ink(0.04f) else Color.Transparent)
                        .clickableNoRipple(source) { app.updateSettings { it.copy(selectedInstance = instance.id) } },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(
                        Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MintColors.Mint.copy(alpha = 0.5f) else MintColors.SandDark.copy(alpha = 0.7f))
                    )
                    Txt(instance.name, manrope(12f, color = MintColors.ink(0.85f)), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun NewsCard(modifier: Modifier) {
    Card(modifier, radius = 15.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 15.dp), spacing = 9.dp) {
        Txt("Что нового", manrope(12.5f, FontWeight.SemiBold))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt("Mint 0.2 — сборка CreateMint и свой сервер", manrope(12f, FontWeight.SemiBold, MintColors.ink(0.85f)))
            Txt(
                "Сборка ставится сама, а кнопка «Сервер» поднимает мир для друзей — без проброса портов.",
                manrope(11.5f, FontWeight.Normal, MintColors.ink(0.78f), lineHeight = 16.7.sp),
                maxLines = 3,
            )
        }
    }
}

private val totalRamGb: Int by lazy {
    val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    ((bean?.totalMemorySize ?: (16L shl 30)) / (1L shl 30) + 1).toInt()
}

fun systemRamGb() = totalRamGb

@Composable
private fun ResourcesCard(app: AppState, modifier: Modifier) {
    val memGb = app.settings.memoryMb / 1024f
    val hw = app.hardware
    Card(modifier, radius = 15.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 15.dp), spacing = 10.dp) {
        Txt("Ресурсы", manrope(12.5f, FontWeight.SemiBold))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ResourceRow("Память", "${formatGb(memGb)} из $totalRamGb ГБ", MintColors.MintDeep)
            ProgressBar(memGb / totalRamGb, Modifier.fillMaxWidth())
            val java = app.javaInfo
            ResourceRow(
                "Java",
                when {
                    java != null -> "${java.version} · ${if (app.settings.javaAuto) "авто" else "вручную"}"
                    app.settings.javaAuto -> "скачается при запуске"
                    else -> "не найдена"
                },
            )
            ResourceRow("Видеокарта", hw?.let { it.gpu ?: "не определена" } ?: "…")
            ResourceRow("Процессор", hw?.let { it.cpu ?: "не определён" } ?: "…")
            ResourceRow("Система", hw?.os ?: "…")
        }
    }
}

@Composable
private fun ResourceRow(label: String, value: String, valueColor: Color = MintColors.ink(0.85f)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(label, manrope(11.5f, color = MintColors.ink(0.85f)))
        Spacer()
        Txt(value, manrope(11.5f, color = valueColor), maxLines = 1)
    }
}

fun formatGb(gb: Float): String = if (gb % 1f == 0f) "${gb.toInt()}" else "%.1f".format(gb)
