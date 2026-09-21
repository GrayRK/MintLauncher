package mint.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.game.ServerAdmin
import mint.game.ServerLauncher
import mint.game.ServerPlayer

/**
 * Вкладка локального сервера: шапка с состоянием и кнопкой вкл/выкл и три раздела —
 * консоль, игроки, управление миром и файлами.
 */
@Composable
fun ServerTab(app: AppState) {
    LaunchedEffect(app.selectedInstance.id) { app.refreshServerData() }
    Column(
        Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Head(app)
        SectionBar(app)
        when (app.serverSection) {
            ServerSection.Console -> ConsoleSection(app, Modifier.weight(1f).fillMaxWidth())
            ServerSection.Players -> PlayersSection(app, Modifier.weight(1f).fillMaxWidth())
            ServerSection.Manage -> ManageSection(app, Modifier.weight(1f).fillMaxWidth())
        }
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
            if (app.server is ServerState.Running) OnlineCounter(app)
            PowerButton(app)
        }
        Status(app)
    }
}

/** «2 / 8» рядом с кнопкой — сколько игроков в сети. */
@Composable
private fun OnlineCounter(app: AppState) {
    val max = app.serverProperties["max-players"] ?: "?"
    Row(
        Modifier.clip(RoundedCornerShape(11.dp)).background(MintColors.ink(0.05f)).padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MintIcon.Users, 15.dp, MintColors.ink(0.6f))
        Txt("${app.onlinePlayers.size} / $max", manrope(12.5f, FontWeight.SemiBold, MintColors.ink(0.85f)))
    }
}

@Composable
private fun PowerButton(app: AppState) {
    val busy = app.serverTask != null
    when (app.server) {
        is ServerState.Preparing -> OutlineButton("Отмена", height = 44.dp, radius = 13.dp) { app.toggleServer() }
        is ServerState.Running -> OutlineButton(
            "Остановить", height = 44.dp, radius = 13.dp,
            leading = { Icon(MintIcon.Stop, 15.dp, MintColors.ink(0.7f)) },
        ) { app.toggleServer() }
        is ServerState.Stopping -> OutlineButton("Остановка…", height = 44.dp, radius = 13.dp, enabled = false) {}
        else -> PrimaryButton(
            "Запустить", height = 44.dp, radius = 13.dp, enabled = !busy,
            textStyle = nunito(15f, color = MintColors.MintInk),
            leading = { Icon(MintIcon.Play, 15.dp, MintColors.MintDarker) },
        ) { app.toggleServer() }
    }
}

/** Ход установки, адреса для друзей или ошибка — смотря что сейчас с сервером. */
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
            if (!state.ready) {
                Txt("Сервер загружает мир…", manrope(11.5f, color = MintColors.ink(0.85f)), maxLines = 1)
                return@Row
            }
            Txt("Сервер работает", manrope(11.5f, FontWeight.SemiBold, MintColors.ink(0.85f)), maxLines = 1)
            Address("дома", ServerLauncher.lanAddress(app.selectedInstance))
            // Внешний IP доступен друзьям, только если на роутере проброшен порт
            app.publicAddress?.let { Address("из интернета", it) }
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

/** Адрес с подписью и кнопкой копирования: «дома 192.168.0.10:25565 ⧉». */
@Composable
private fun Address(label: String, address: String) {
    val (source, hovered) = rememberHover()
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (hovered) MintColors.Mint.copy(alpha = 0.25f) else MintColors.ink(0.05f))
            .clickableNoRipple(source) { copyToClipboard(address) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, manrope(11f, color = MintColors.ink(0.55f)))
        Txt(address, Mono.copy(fontSize = 11.5.sp, color = MintColors.ink(0.9f)))
        Icon(MintIcon.Copy, 12.dp, if (hovered) MintColors.MintDeep else MintColors.ink(0.45f))
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Переключатель разделов и строка итога последней операции. */
@Composable
private fun SectionBar(app: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val sections = ServerSection.entries
        Segmented(
            listOf("Консоль", "Игроки", "Управление"),
            sections.indexOf(app.serverSection),
            { app.serverSection = sections[it] },
            Modifier.width(360.dp),
        )
        val task = app.serverTask
        val notice = app.serverNotice
        when {
            task != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(null, Modifier.width(90.dp), height = 5.dp)
                Txt("$task…", manrope(11.5f, color = MintColors.ink(0.75f)), maxLines = 1)
            }
            notice != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (notice.error) MintIcon.Warning else MintIcon.Check, 14.dp, if (notice.error) MintColors.Danger else MintColors.MintDeep)
                Txt(notice.text, manrope(11.5f, color = if (notice.error) MintColors.Danger else MintColors.ink(0.75f)), maxLines = 2)
            }
        }
    }
}

// ---- Консоль ----

@Composable
private fun ConsoleSection(app: AppState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Console(app, Modifier.weight(1f).fillMaxWidth())
        QuickCommands(app)
        CommandInput(app)
    }
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

/** Частые команды одним нажатием. */
@Composable
private fun QuickCommands(app: AppState) {
    val enabled = app.serverAcceptsCommands
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "День" to "time set day",
            "Ясная погода" to "weather clear",
            "Сохранить мир" to "save-all",
            "Кто в сети" to "list",
        ).forEach { (label, command) -> Pill(label, enabled) { app.sendServerCommand(command) } }
    }
}

@Composable
private fun Pill(text: String, enabled: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.height(30.dp).clip(shape)
            .background(if (hovered && enabled) MintColors.Mint.copy(alpha = 0.3f) else MintColors.ink(0.05f))
            .clickableNoRipple(source, enabled, onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, manrope(12f, FontWeight.SemiBold, MintColors.ink(if (enabled) 0.8f else 0.35f)))
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

// ---- Игроки ----

@Composable
private fun PlayersSection(app: AppState, modifier: Modifier) {
    val players = app.playerList
    val online = players.filter { app.isOnline(it) }
    val offline = players.filterNot { app.isOnline(it) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AccessCard(app) }
        if (players.isEmpty()) {
            item {
                Txt(
                    "Здесь появятся игроки, которые заходили на сервер, а также все из белого списка и админов.",
                    manrope(12f, color = MintColors.ink(0.5f)),
                    Modifier.padding(top = 18.dp, start = 4.dp),
                )
            }
        }
        if (online.isNotEmpty()) {
            item { GroupTitle("В игре · ${online.size}") }
            items(online, key = { "on-" + it.name }) { PlayerRow(app, it, online = true) }
        }
        if (offline.isNotEmpty()) {
            item { GroupTitle(if (online.isEmpty()) "Игроки сервера" else "Не в сети") }
            items(offline, key = { "off-" + it.name }) { PlayerRow(app, it, online = false) }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Txt(
        text.uppercase(),
        manrope(10.5f, FontWeight.SemiBold, MintColors.ink(0.5f), letterSpacing = 1.2.sp),
        Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
    )
}

/**
 * Белый список и добавление игрока по нику. Сервер открыт в интернет,
 * поэтому без белого списка на него может зайти кто угодно с аккаунтом Ely.by.
 */
@Composable
private fun AccessCard(app: AppState) {
    var nick by remember { mutableStateOf("") }
    val canAdd = app.serverAcceptsCommands
    Card(Modifier.fillMaxWidth(), radius = 16.dp, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(MintIcon.ListChecks, 18.dp, MintColors.MintDeep)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt("Белый список", manrope(13.5f, FontWeight.SemiBold))
                Txt(
                    if (app.whitelistEnabled) "Включён: заходят только игроки из списка."
                    else "Выключен: зайти может любой, кто знает адрес. Для сервера в интернете лучше включить.",
                    manrope(11.5f, color = if (app.whitelistEnabled) MintColors.ink(0.6f) else MintColors.Danger),
                )
            }
            Toggle(app.whitelistEnabled) { app.setWhitelistEnabled(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val add = { op: Boolean -> if (app.addPlayer(nick, op)) nick = "" }
            MintTextField(
                nick, { nick = it.take(16) }, Modifier.weight(1f),
                placeholder = if (canAdd) "Ник игрока" else "Добавить новый ник можно, пока сервер работает",
                enabled = canAdd,
                onSubmit = { add(false) },
            )
            OutlineButton(
                "В белый список", height = 46.dp, radius = 12.dp, enabled = canAdd && nick.isNotBlank(),
                leading = { Icon(MintIcon.ListChecks, 15.dp, MintColors.ink(0.7f)) },
            ) { add(false) }
            OutlineButton(
                "Сделать админом", height = 46.dp, radius = 12.dp, enabled = canAdd && nick.isNotBlank(),
                leading = { Icon(MintIcon.Crown, 15.dp, MintColors.ink(0.7f)) },
            ) { add(true) }
        }
    }
}

@Composable
private fun PlayerRow(app: AppState, player: ServerPlayer, online: Boolean) {
    LaunchedEffect(player.uuid) { app.loadPlayerSkin(player.uuid) }
    val shape = RoundedCornerShape(14.dp)
    val (source, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(MintColors.Surface)
            .border(1.dp, if (hovered) MintColors.MintFocus.copy(alpha = 0.5f) else MintColors.ink(0.07f), shape)
            .clickableNoRipple(source, enabled = false) {}
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            SkinHead(app.playerSkin(player.uuid), 38.dp, 9.dp)
            if (online) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(0.dp).size(12.dp).clip(CircleShape)
                        .background(MintColors.Surface).padding(2.dp).clip(CircleShape).background(MintColors.MintDeep)
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(player.name, nunito(15f), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (online) Badge("в игре", MintColors.MintDeep)
                if (player.op) Badge("админ", MintColors.MintDeep)
                if (player.whitelisted) Badge("в белом списке", MintColors.ink(0.6f))
                if (player.banned) Badge("забанен", MintColors.Danger)
            }
        }
        // Игрок, которого сервер ещё не записал (нет UUID), выключенному серверу неизвестен
        val known = player.uuid.isNotEmpty() || app.serverAcceptsCommands
        ActionButton(
            MintIcon.Crown, if (player.op) "Снять права администратора" else "Сделать администратором",
            active = player.op, enabled = known,
        ) { app.setOp(player, !player.op) }
        ActionButton(
            MintIcon.ListChecks, if (player.whitelisted) "Убрать из белого списка" else "Добавить в белый список",
            active = player.whitelisted, enabled = known,
        ) { app.setWhitelisted(player, !player.whitelisted) }
        ActionButton(
            MintIcon.SignOut, if (online) "Отключить от сервера" else "Отключить можно только игрока в сети",
            enabled = online && app.serverAcceptsCommands,
        ) { app.kick(player) }
        ActionButton(
            MintIcon.Prohibit, if (player.banned) "Разбанить" else "Забанить: отключить и не пускать",
            active = player.banned, danger = true, enabled = known,
        ) { app.setBanned(player, !player.banned) }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) { Txt(text, manrope(10.5f, FontWeight.SemiBold, color)) }
}

/** Квадратная кнопка-иконка с подсказкой; [active] — включённое состояние (админ, в списке, бан). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    icon: MintIcon,
    hint: String,
    active: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberHover()
    val accent = if (danger) MintColors.Danger else MintColors.MintDeep
    val shape = RoundedCornerShape(10.dp)
    TooltipArea(tooltip = { Tooltip(hint) }, delayMillis = 350) {
        Box(
            Modifier.size(34.dp).clip(shape)
                .background(
                    when {
                        active && danger -> MintColors.Danger.copy(alpha = 0.16f)
                        active -> MintColors.Mint
                        hovered && enabled -> accent.copy(alpha = 0.12f)
                        else -> Color.Transparent
                    }
                )
                .border(1.dp, if (active) Color.Transparent else MintColors.ink(0.1f), shape)
                .clickableNoRipple(source, enabled, onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, 16.dp,
                when {
                    !enabled -> MintColors.ink(0.25f)
                    active && danger -> MintColors.Danger
                    active -> MintColors.MintDarker
                    hovered -> accent
                    else -> MintColors.ink(0.6f)
                },
            )
        }
    }
}

@Composable
private fun Tooltip(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.clip(shape).background(MintColors.Window).border(1.dp, MintColors.ink(0.12f), shape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) { Txt(text, manrope(11.5f, color = MintColors.ink(0.9f))) }
}

// ---- Управление ----

@Composable
private fun ManageSection(app: AppState, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!app.serverStopped) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MintIcon.Warning, 14.dp, MintColors.ink(0.55f))
                Txt(
                    "Пока сервер работает, мир и настройки менять нельзя — он перезапишет их своими. Остановите сервер.",
                    manrope(11.5f, color = MintColors.ink(0.65f)),
                )
            }
        }
        WorldCard(app)
        SettingsCard(app)
        DangerCard(app)
    }
}

@Composable
private fun SectionCard(icon: MintIcon, title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), radius = 16.dp, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MintColors.Mint.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, 17.dp, MintColors.MintDarker) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(title, nunito(16f))
                Txt(subtitle, manrope(11.5f, color = MintColors.ink(0.6f)))
            }
        }
        content()
    }
}

@Composable
private fun WorldCard(app: AppState) {
    val instance = app.selectedInstance
    val enabled = app.serverStopped && app.serverTask == null
    // Размер считается обходом папки — не в потоке интерфейса
    val world by produceState<Pair<String, Long>?>(null, instance.id, app.server, app.serverTask) {
        value = withContext(Dispatchers.IO) {
            val dir = ServerAdmin.worldDir(instance)
            if (dir.isDirectory) dir.name to ServerAdmin.sizeOf(dir) else null
        }
    }
    val stale = remember(instance.id, app.server, app.serverTask) { ServerLauncher.worldMissesDatapacks(instance) }
    var confirm by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf(true) }

    SectionCard(
        MintIcon.Globe, "Мир",
        world?.let { (name, size) -> "Папка $name · ${formatSize(size)}" } ?: "Мира ещё нет — он создастся при первом запуске",
    ) {
        if (stale) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(MintIcon.Warning, 14.dp, MintColors.Danger)
                Txt(
                    "Мир создан без пресета генерации сборки — рельеф в нём ванильный. Генератор записывается в мир " +
                        "при создании и потом не меняется: чтобы получить рельеф сборки, пересоздайте мир.",
                    manrope(11.5f, color = MintColors.Danger),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton(
                "Резервная копия", height = 40.dp, radius = 12.dp, enabled = enabled && world != null,
                leading = { Icon(MintIcon.Archive, 15.dp, MintColors.ink(0.7f)) },
            ) { app.backupWorld() }
            OutlineButton(
                "Папка копий", height = 40.dp, radius = 12.dp,
                leading = { Icon(MintIcon.FolderOpen, 15.dp, MintColors.ink(0.7f)) },
            ) { app.openBackupsFolder() }
            Spacer()
            if (!confirm) DangerButton("Пересоздать мир", MintIcon.Refresh, enabled) { confirm = true }
        }
        if (confirm) {
            ConfirmBox(
                "Текущий мир удалится вместе с Незером, Эндом и постройками. Сервер сразу запустится и создаст новый.",
                action = "Удалить и создать новый",
                enabled = enabled,
                onCancel = { confirm = false },
                onConfirm = {
                    confirm = false
                    app.resetWorld(seed, backup)
                    seed = ""
                },
            ) {
                MintTextField(seed, { seed = it }, Modifier.fillMaxWidth(), placeholder = "Сид нового мира — пусто, значит случайный")
                Checkbox(backup, "Сначала сохранить копию текущего мира") { backup = it }
            }
        }
    }
}

/** Главное из server.properties; остальное — в самом файле через «Папку сервера». */
@Composable
private fun SettingsCard(app: AppState) {
    val props = app.serverProperties
    val enabled = app.serverStopped && app.serverTask == null && props.isNotEmpty()
    var motd by remember(props) { mutableStateOf(props["motd"].orEmpty()) }
    var maxPlayers by remember(props) { mutableStateOf(props["max-players"].orEmpty()) }
    var difficulty by remember(props) { mutableStateOf(props["difficulty"] ?: "normal") }
    var pvp by remember(props) { mutableStateOf(props["pvp"] != "false") }
    val difficulties = listOf("peaceful", "easy", "normal", "hard")

    SectionCard(
        MintIcon.Sliders, "Настройки сервера",
        if (props.isEmpty()) "Появятся после первого запуска сервера" else "Применяются при следующем запуске",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Название в списке серверов", Modifier.weight(1f)) {
                MintTextField(motd, { motd = it }, Modifier.fillMaxWidth(), enabled = enabled)
            }
            Field("Максимум игроков", Modifier.width(150.dp)) {
                MintTextField(maxPlayers, { maxPlayers = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), enabled = enabled)
            }
        }
        Field("Сложность") {
            Segmented(
                listOf("Мирная", "Лёгкая", "Нормальная", "Сложная"),
                difficulties.indexOf(difficulty).coerceAtLeast(0),
                { if (enabled) difficulty = difficulties[it] },
                Modifier.width(440.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt("Сражения между игроками (PvP)", manrope(13f, FontWeight.SemiBold))
                Txt("Если выключить, игроки не смогут ранить друг друга.", manrope(11.5f, color = MintColors.ink(0.6f)))
            }
            Toggle(pvp) { if (enabled) pvp = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Сохранить", height = 40.dp, radius = 12.dp, enabled = enabled, textStyle = manrope(13f, FontWeight.SemiBold, MintColors.MintInk)) {
                app.saveServerProperties(
                    mapOf(
                        "motd" to motd,
                        "max-players" to (maxPlayers.toIntOrNull()?.coerceIn(1, 999) ?: 8).toString(),
                        "difficulty" to difficulty,
                        "pvp" to pvp.toString(),
                    )
                )
            }
            OutlineButton(
                "Папка сервера", height = 40.dp, radius = 12.dp,
                leading = { Icon(MintIcon.FolderOpen, 15.dp, MintColors.ink(0.7f)) },
            ) { app.openServerFolder() }
        }
    }
}

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, manrope(11.5f, FontWeight.SemiBold, MintColors.ink(0.65f)))
        content()
    }
}

@Composable
private fun DangerCard(app: AppState) {
    val enabled = app.serverStopped && app.serverTask == null
    var confirm by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf(true) }
    SectionCard(
        MintIcon.Trash, "Удалить сервер",
        "Стирает папку server целиком: NeoForge, мир, конфиги, списки игроков. Следующий запуск поставит всё с нуля.",
    ) {
        if (!confirm) {
            Row { DangerButton("Удалить сервер", MintIcon.Trash, enabled) { confirm = true } }
        } else {
            ConfirmBox(
                "Сервер удалится полностью, отменить это нельзя. Резервные копии в server-backups останутся.",
                action = "Удалить навсегда",
                enabled = enabled,
                onCancel = { confirm = false },
                onConfirm = {
                    confirm = false
                    app.deleteServer(backup)
                },
            ) {
                Checkbox(backup, "Сначала сохранить копию мира") { backup = it }
            }
        }
    }
}

/** Подтверждение необратимого действия прямо в карточке — без всплывающих окон. */
@Composable
private fun ConfirmBox(
    text: String,
    action: String,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    extra: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(MintColors.Danger.copy(alpha = 0.07f))
            .border(1.dp, MintColors.Danger.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(MintIcon.Warning, 15.dp, MintColors.Danger)
            Txt(text, manrope(12f, color = MintColors.ink(0.85f)))
        }
        extra()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DangerButton(action, MintIcon.Trash, enabled, filled = true, onClick = onConfirm)
            OutlineButton("Отмена", height = 40.dp, radius = 12.dp, onClick = onCancel)
        }
    }
}

@Composable
private fun DangerButton(text: String, icon: MintIcon, enabled: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(12.dp)
    val active = hovered && enabled
    val content = when {
        !enabled -> MintColors.ink(0.35f)
        filled -> MintColors.Surface
        else -> MintColors.Danger
    }
    Row(
        Modifier.height(40.dp).clip(shape)
            .background(
                when {
                    filled && enabled -> MintColors.Danger.copy(alpha = if (active) 0.85f else 1f)
                    active -> MintColors.Danger.copy(alpha = 0.1f)
                    else -> Color.Transparent
                }
            )
            .border(1.dp, if (enabled) MintColors.Danger.copy(alpha = 0.55f) else MintColors.ink(0.12f), shape)
            .clickableNoRipple(source, enabled, onClick)
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, 15.dp, content)
        Txt(text, manrope(13f, FontWeight.SemiBold, content), maxLines = 1)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f КБ".format(bytes / 1024.0)
}
