package mint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mint.game.ServerLauncher

/**
 * Вкладка локального сервера: в доке видна, пока сервер не остановлен.
 *
 * Здесь консоль с вводом команд и кнопка вкл/выкл — то, что раньше приходилось
 * ужимать в hero на главной.
 */
@Composable
fun ServerTab(app: AppState) {
    Column(
        Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Head(app)
        Console(app, Modifier.weight(1f).fillMaxWidth())
        CommandInput(app)
    }
}

/** Шапка: что за сборка, что с сервером сейчас и кнопка включения. */
@Composable
private fun Head(app: AppState) {
    Card(
        Modifier.fillMaxWidth(),
        radius = 16.dp,
        padding = PaddingValues(horizontal = 20.dp, vertical = 17.dp),
        spacing = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MintColors.Mint.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { Icon(MintIcon.Server, 19.dp, MintColors.MintDarker) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt("СЕРВЕР СБОРКИ", manrope(10.5f, FontWeight.SemiBold, MintColors.MintDeep, letterSpacing = 1.4.sp))
                Txt(app.selectedInstance.name, nunito(19f), maxLines = 1)
            }
            Spacer()
            PowerButton(app)
        }
        Status(app)
    }
}

@Composable
private fun PowerButton(app: AppState) {
    when (app.server) {
        is ServerState.Preparing -> OutlineButton("Отмена", height = 44.dp, radius = 13.dp) { app.toggleServer() }
        is ServerState.Running -> OutlineButton(
            "Остановить", height = 44.dp, radius = 13.dp,
            leading = { Icon(MintIcon.Stop, 15.dp, MintColors.ink(0.7f)) },
        ) { app.toggleServer() }
        is ServerState.Stopping -> OutlineButton("Остановка…", height = 44.dp, radius = 13.dp, enabled = false) {}
        else -> PrimaryButton(
            "Запустить", height = 44.dp, radius = 13.dp,
            textStyle = nunito(15f, color = MintColors.MintInk),
            leading = { Icon(MintIcon.Play, 15.dp, MintColors.MintDarker) },
        ) { app.toggleServer() }
    }
}

/** Ход установки, адрес для друзей или ошибка — смотря что сейчас с сервером. */
@Composable
private fun Status(app: AppState) {
    when (val state = app.server) {
        is ServerState.Preparing -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Txt(state.stage, manrope(11.5f, color = MintColors.ink(0.85f)), maxLines = 1)
            ProgressBar(state.fraction, Modifier.fillMaxWidth())
        }

        is ServerState.Running -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (state.ready) MintColors.MintDeep else MintColors.ink(0.35f))
            val hub = ServerLauncher.TUNNEL_HUB
            val local = "localhost:${ServerLauncher.DEFAULT_PORT}"
            val label = when {
                !state.ready -> "Сервер загружает мир…"
                // Туннель не выдаёт свой адрес: друзья заходят на хаб и выбирают сервер по названию
                state.tunnel -> "Сервер работает · друзьям: $hub, там выбрать «${app.selectedInstance.name}»"
                else -> "Сервер работает · $local · туннель не поднялся"
            }
            Txt(label, manrope(11.5f, color = MintColors.ink(0.85f)), maxLines = 1)
            if (state.ready) LinkText("копировать") { copyToClipboard(if (state.tunnel) hub else local) }
        }

        is ServerState.Stopping -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(MintColors.ink(0.35f))
            Txt("Сервер сохраняет мир и выключается…", manrope(11.5f, color = MintColors.ink(0.85f)))
        }

        is ServerState.Failed -> Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(MintIcon.Warning, 14.dp, MintColors.Danger)
                Txt(state.message, manrope(11.5f, color = MintColors.Danger), maxLines = 3)
            }
            // EULA принимает игрок — лаунчер не может согласиться за него
            if (!app.settings.eulaAccepted) {
                LinkText("Принимаю EULA Minecraft и запускаю сервер") {
                    app.acceptEula()
                    app.toggleServer()
                }
            }
        }

        ServerState.Idle -> Txt(
            "Сервер остановлен. Мир и настройки лежат в папке сборки — при следующем запуске всё на месте.",
            manrope(11.5f, color = MintColors.ink(0.6f)),
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun Console(app: AppState, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(app.serverLines.size) {
        if (app.serverLines.isNotEmpty()) state.scrollToItem(app.serverLines.size - 1)
    }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.clip(shape).background(MintColors.Surface).border(1.dp, MintColors.ink(0.07f), shape)) {
        if (app.serverLines.isEmpty()) {
            Txt(
                "Здесь появится вывод сервера.",
                manrope(11.5f, color = MintColors.ink(0.45f)),
                Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(14.dp)) {
            items(app.serverLines) { line ->
                // Свои команды выделяем мятным — иначе теряются в потоке сервера
                val own = line.startsWith("> ")
                Txt(
                    line,
                    Mono.copy(fontSize = 11.sp, color = if (own) MintColors.MintDeep else MintColors.ink(0.8f)),
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun CommandInput(app: AppState) {
    var command by remember { mutableStateOf("") }
    val ready = app.serverAcceptsCommands
    MintTextField(
        value = command,
        onValueChange = { command = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = when {
            ready -> "Команда сервера: op Ник, whitelist add Ник, time set day…"
            app.server is ServerState.Stopping -> "Сервер выключается…"
            app.server is ServerState.Preparing -> "Сервер ещё запускается…"
            else -> "Сервер не запущен"
        },
        enabled = ready,
        onSubmit = {
            app.sendServerCommand(command)
            command = ""
        },
    )
}
