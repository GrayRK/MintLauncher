package mint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import mint.core.Updater

/** Автообновление при запуске: лаунчер ещё не открыт, идёт проверка или загрузка новой версии. */
@Composable
fun FrameWindowScope.UpdateScreen(app: AppState, actions: WindowActions) {
    Box(Modifier.fillMaxSize()) {
        WindowDraggableArea(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)) {
            WindowControls(actions, maximizable = false, tint = MintColors.ink(0.45f))
        }
        Column(
            Modifier.width(360.dp).align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Logo(56.dp, 16.dp, 28f)
            val (title, fraction) = when (val u = app.update) {
                is UpdateState.Downloading -> "Загрузка Mint ${u.release.version}" to u.fraction
                is UpdateState.Restarting -> "Перезапуск на Mint ${u.release.version}" to null
                else -> "Проверка обновлений" to null
            }
            Txt(title, nunito(19f, color = MintColors.InkStrong))
            ProgressBar(fraction, Modifier.fillMaxWidth())
            Txt(
                if (fraction != null) "${(fraction * 100).toInt()}%" else "Сейчас установлена ${Updater.currentVersion}",
                manrope(12f, color = MintColors.ink(0.7f)),
            )
        }
    }
}

/** Плашка в заголовке: вышла новая версия — одна кнопка, чтобы обновиться. */
@Composable
fun UpdateChip(app: AppState) {
    val (text, action) = when (val u = app.update) {
        is UpdateState.Available -> "Вышла версия ${u.release.version}" to "Обновить"
        is UpdateState.Downloading -> "Загрузка ${u.release.version} · ${(u.fraction * 100).toInt()}%" to null
        is UpdateState.Restarting -> "Перезапуск…" to null
        else -> return
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(MintColors.Mint.copy(alpha = 0.28f))
            .border(1.dp, MintColors.MintBorder.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = if (action != null) 3.dp else 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(text, manrope(11.5f, FontWeight.SemiBold, MintColors.MintDeep))
        if (action != null) {
            PrimaryButton(
                action, height = 24.dp, radius = 8.dp,
                textStyle = manrope(11.5f, FontWeight.Bold, MintColors.MintInk),
                onClick = { app.tab = Tab.Settings; app.installUpdate() },
            )
        }
    }
}

/** Раздел «О лаунчере» в настройках: версия, обновления, автообновление. */
@Composable
fun AboutLauncher(app: AppState) {
    Card(spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt("Mint ${Updater.currentVersion}", manrope(13.5f, FontWeight.SemiBold))
                val (status, error) = when (val u = app.update) {
                    UpdateState.None -> "Установлена последняя версия" to false
                    UpdateState.Checking -> "Проверка обновлений…" to false
                    is UpdateState.Available -> "Доступна версия ${u.release.version}" to false
                    is UpdateState.Downloading -> "Загрузка ${u.release.version} · ${(u.fraction * 100).toInt()}%" to false
                    is UpdateState.Restarting -> "Перезапуск…" to false
                    is UpdateState.Failed -> u.message to true
                }
                Txt(status, manrope(11.5f, FontWeight.Normal, if (error) MintColors.Danger else MintColors.ink(0.78f)), maxLines = 2)
            }
            when (val u = app.update) {
                is UpdateState.Available, is UpdateState.Failed -> {
                    val release = (u as? UpdateState.Available)?.release ?: (u as UpdateState.Failed).release
                    if (release != null) {
                        PrimaryButton(
                            "Обновить до ${release.version}", height = 40.dp, radius = 11.dp,
                            textStyle = manrope(12.5f, FontWeight.Bold, MintColors.MintInk),
                            enabled = app.updateBlocker == null,
                            onClick = app::installUpdate,
                        )
                    } else {
                        CheckButton(app)
                    }
                }
                UpdateState.None -> CheckButton(app)
                else -> {}
            }
        }
        (app.update as? UpdateState.Downloading)?.let { ProgressBar(it.fraction, Modifier.fillMaxWidth()) }
        val notes = when (val u = app.update) {
            is UpdateState.Available -> u.release.notes
            is UpdateState.Downloading -> u.release.notes
            else -> ""
        }
        if (notes.isNotBlank()) {
            // Описание релиза пишется в markdown — показываем как простой текст
            Txt(
                notes.replace("**", "").replace("`", "").trim(),
                manrope(11.5f, FontWeight.Normal, MintColors.ink(0.78f)), maxLines = 8,
            )
        }
        app.updateBlocker?.takeIf { app.update is UpdateState.Available }?.let {
            Txt(it, manrope(11.5f, FontWeight.Normal, MintColors.ink(0.6f)))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MintColors.ink(0.06f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt("Автообновление", manrope(13.5f, FontWeight.SemiBold))
                Txt(
                    "При запуске лаунчер сам скачает новую версию и откроется уже обновлённым",
                    manrope(11.5f, FontWeight.Normal, MintColors.ink(0.78f)),
                )
            }
            Toggle(app.settings.autoUpdate, app::toggleAutoUpdate)
        }
        Txt("Данные: ${mint.core.MintPaths.home.path}", manrope(11.5f, FontWeight.Normal, MintColors.ink(0.6f)), maxLines = 2)
    }
}

@Composable
private fun CheckButton(app: AppState) {
    OutlineButton("Проверить обновления", height = 40.dp, radius = 11.dp, textStyle = manrope(12.5f, FontWeight.SemiBold)) {
        app.checkUpdateNow()
    }
}
