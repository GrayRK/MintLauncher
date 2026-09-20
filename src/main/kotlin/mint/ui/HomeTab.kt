package mint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
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
    val art = rememberArt(instance.banner)
    val onArt = art != null

    // Поверх арта чернильные цвета не читаются — берём светлую пару
    val accent = if (onArt) MintColors.OnArtAccent else MintColors.MintDeep
    val title = if (onArt) MintColors.OnArt else MintColors.InkStrong
    val body = if (onArt) MintColors.onArt(0.82f) else MintColors.ink(0.78f)
    val soft = if (onArt) MintColors.onArt(0.9f) else MintColors.ink(0.85f)
    val danger = if (onArt) MintColors.OnArtDanger else MintColors.Danger
    val track = if (onArt) MintColors.onArt(0.22f) else MintColors.ink(0.08f)

    Box(
        modifier.clip(shape)
            .background(Brush.linearGradient(listOf(MintColors.HeroTop, MintColors.HeroBottom), start = Offset(0f, 0f), end = Offset(600f, 1400f)))
            .border(1.dp, MintColors.ink(0.07f), shape)
    ) {
        if (art != null) {
            Image(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Затемнение снизу: под ним живут название сборки и кнопки
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to MintColors.ArtScrim.copy(alpha = 0.30f),
                        1f to MintColors.ArtScrim,
                    )
                )
            )
        } else {
            DiagonalStripes(Modifier.fillMaxSize())
        }

        val launch = app.launch
        val consoleArea = Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 130.dp).fillMaxHeight()
        when {
            // Сервер важнее: им управляют отсюда, а игра пишет в свой лог
            app.server !is ServerState.Idle -> ServerConsole(app, consoleArea, onArt)
            app.settings.showConsole && app.consoleLines.isNotEmpty() -> Console(app, consoleArea)
            else -> {}
        }

        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 26.dp, end = 26.dp, bottom = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Txt("ПРОДОЛЖИТЬ", manrope(10.5f, FontWeight.SemiBold, accent, letterSpacing = 1.4.sp))
                Txt(instance.name, nunito(30f, color = title, lineHeight = 34.sp), maxLines = 1)
                val subtitle = instance.subtitle.let {
                    if (instance.loaderVersion.isNotBlank()) it.replace("NeoForge", "NeoForge ${instance.loaderVersion}") else it
                }
                Txt(subtitle, manrope(12.5f, FontWeight.Normal, body))
                when (launch) {
                    is LaunchState.Preparing -> Column(Modifier.padding(top = 10.dp).widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Txt(launch.stage, manrope(11.5f, color = soft), maxLines = 1)
                        ProgressBar(launch.fraction, Modifier.fillMaxWidth(), track = track)
                    }
                    is LaunchState.Failed -> Row(
                        Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(MintIcon.Warning, 14.dp, danger)
                        Txt(launch.message, manrope(11.5f, color = danger), maxLines = 3)
                    }
                    else -> {}
                }
                ServerStatus(app, onArt)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                ServerButton(app, onArt)
                when (launch) {
                    is LaunchState.Preparing -> OutlineButton(
                        "Отмена", height = 52.dp, radius = 15.dp, onArt = onArt,
                        textStyle = manrope(13.5f, FontWeight.SemiBold, if (onArt) MintColors.OnArt else MintColors.Ink),
                    ) { app.cancelLaunch() }
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

/** Арт сборки читается с диска один раз; файла нет или он битый — рисуем градиент. */
@Composable
private fun rememberArt(file: java.io.File?): ImageBitmap? {
    if (file == null) return null
    return remember(file.path, file.lastModified()) {
        runCatching { file.inputStream().buffered().use { loadImageBitmap(it) } }.getOrNull()
    }
}

/** Кнопка локального сервера: поднять сборку для друзей и остановить её. */
@Composable
private fun ServerButton(app: AppState, onArt: Boolean) {
    val tint = if (onArt) MintColors.onArt(0.85f) else MintColors.ink(0.7f)
    val text = manrope(13.5f, FontWeight.SemiBold, if (onArt) MintColors.OnArt else MintColors.Ink)
    when (app.server) {
        is ServerState.Preparing -> OutlineButton(
            "Отмена сервера", height = 52.dp, radius = 15.dp, onArt = onArt, textStyle = text,
        ) { app.toggleServer() }
        is ServerState.Running -> OutlineButton(
            "Остановить", height = 52.dp, radius = 15.dp, onArt = onArt, textStyle = text,
            leading = { Icon(MintIcon.Stop, 16.dp, tint) },
        ) { app.toggleServer() }
        is ServerState.Stopping -> OutlineButton(
            "Остановка…", height = 52.dp, radius = 15.dp, enabled = false, onArt = onArt, textStyle = text,
        ) {}
        else -> OutlineButton(
            "Сервер", height = 52.dp, radius = 15.dp, onArt = onArt, textStyle = text,
            leading = { Icon(MintIcon.Server, 16.dp, tint) },
        ) { app.toggleServer() }
    }
}

/** Ход запуска сервера, его адрес и согласие с EULA. */
@Composable
private fun ServerStatus(app: AppState, onArt: Boolean) {
    when (val state = app.server) {
        is ServerState.Preparing -> Column(
            Modifier.padding(top = 10.dp).widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Txt(state.stage, manrope(11.5f, color = if (onArt) MintColors.onArt(0.9f) else MintColors.ink(0.85f)), maxLines = 1)
            ProgressBar(
                state.fraction, Modifier.fillMaxWidth(),
                track = if (onArt) MintColors.onArt(0.22f) else MintColors.ink(0.08f),
            )
        }

        is ServerState.Running -> Row(
            Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MintIcon.Server, 14.dp, if (onArt) MintColors.OnArtAccent else MintColors.MintDeep)
            val hub = mint.game.ServerLauncher.TUNNEL_HUB
            val label = when {
                !state.ready -> "Сервер загружается…"
                // Туннель не выдаёт свой адрес: друзья заходят на хаб и выбирают сервер по названию
                state.tunnel -> "Сервер работает · друзьям: $hub, там выбрать «${app.selectedInstance.name}»"
                else -> "Сервер работает · localhost:${mint.game.ServerLauncher.DEFAULT_PORT} · туннель не поднялся"
            }
            Txt(label, manrope(11.5f, color = if (onArt) MintColors.onArt(0.9f) else MintColors.ink(0.85f)), maxLines = 1)
            if (state.ready) {
                val copied = if (state.tunnel) hub else "localhost:${mint.game.ServerLauncher.DEFAULT_PORT}"
                LinkText("копировать") { copyToClipboard(copied) }
            }
        }

        is ServerState.Stopping -> Txt(
            "Сервер сохраняет мир и выключается…",
            manrope(11.5f, color = if (onArt) MintColors.onArt(0.9f) else MintColors.ink(0.85f)),
            Modifier.padding(top = 10.dp),
        )

        is ServerState.Failed -> Column(
            Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                val danger = if (onArt) MintColors.OnArtDanger else MintColors.Danger
                Icon(MintIcon.Warning, 14.dp, danger)
                Txt(state.message, manrope(11.5f, color = danger), maxLines = 3)
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

/**
 * Консоль сервера с полем ввода: сюда пишут op, whitelist, weather и прочее.
 * Появляется, как только сервер начал подниматься, и живёт до его остановки.
 */
@Composable
private fun ServerConsole(app: AppState, modifier: Modifier, onArt: Boolean) {
    val state = rememberLazyListState()
    LaunchedEffect(app.serverLines.size) {
        if (app.serverLines.isNotEmpty()) state.scrollToItem(app.serverLines.size - 1)
    }
    var command by remember { mutableStateOf("") }
    val send = {
        app.sendServerCommand(command)
        command = ""
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(MintIcon.Server, 13.dp, if (onArt) MintColors.OnArtAccent else MintColors.MintDeep)
            Txt(
                "Консоль сервера",
                manrope(11.5f, FontWeight.SemiBold, if (onArt) MintColors.OnArt else MintColors.ink(0.85f)),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MintColors.Surface.copy(alpha = 0.9f)),
            state = state,
            contentPadding = PaddingValues(12.dp),
        ) {
            items(app.serverLines) { line ->
                // Свои команды выделяем мятным — иначе теряются в потоке сервера
                val own = line.startsWith("> ")
                Txt(
                    line,
                    Mono.copy(
                        fontSize = 10.5.sp,
                        color = if (own) MintColors.MintDeep else MintColors.ink(0.8f),
                    ),
                    maxLines = 2,
                )
            }
        }
        val ready = app.serverAcceptsCommands
        MintTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = if (ready) "Команда сервера: op Ник, whitelist add Ник, time set day…" else "Сервер ещё запускается…",
            enabled = ready,
            onSubmit = send,
        )
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
