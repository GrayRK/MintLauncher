package mint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.foundation.window.WindowDraggableArea

@Composable
fun FrameWindowScope.LoginScreen(app: AppState, actions: WindowActions) {
    val settings = app.settings
    var login by remember { mutableStateOf(settings.lastLogin) }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(settings.authServer.ifBlank { "ely.by" }) }
    var remember by remember { mutableStateOf(settings.rememberMe) }
    var showServer by remember { mutableStateOf(settings.authServer.isNotBlank() && settings.authServer != "ely.by") }

    val submit = { app.login(server, login, password, totp, remember) }

    Box(Modifier.fillMaxSize()) {
        // Всё окно можно перетаскивать за пустые области
        WindowDraggableArea(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }

        Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)) {
            WindowControls(actions, maximizable = false, tint = MintColors.ink(0.45f))
        }

        Column(
            Modifier.fillMaxHeight().width(384.dp).align(Alignment.TopCenter).padding(top = 56.dp, bottom = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Logo(30.dp, 10.dp, 16f)
                Txt("Mint", nunito(19f))
            }

            Column(Modifier.padding(top = 52.dp)) {
                Txt("С возвращением", nunito(30f, color = MintColors.InkStrong, lineHeight = 36.sp))
                Txt(
                    "Войдите через Ely.by, чтобы играть со скином и на серверах с авторизацией.",
                    manrope(13.5f, FontWeight.Normal, MintColors.ink(0.78f), lineHeight = 21.6.sp),
                    Modifier.padding(top = 9.dp).widthIn(max = 300.dp),
                )
            }

            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Ник или e-mail") {
                    MintTextField(login, { login = it }, Modifier.fillMaxWidth(), placeholder = "Steve", enabled = !app.loginBusy, onSubmit = submit)
                }
                Field("Пароль") {
                    MintTextField(password, { password = it }, Modifier.fillMaxWidth(), password = true, enabled = !app.loginBusy, onSubmit = submit)
                }
                if (app.needsTotp) {
                    Field("Код 2FA") {
                        MintTextField(totp, { totp = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), placeholder = "123456", onSubmit = submit)
                    }
                }
                if (showServer) {
                    Field("Сервер авторизации") {
                        MintTextField(server, { server = it }, Modifier.fillMaxWidth(), placeholder = "ely.by или https://…/api/yggdrasil")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, "Запомнить меня") { remember = it }
                    Spacer()
                    LinkText(if (showServer) "Ely.by" else "Свой сервер") {
                        showServer = !showServer
                        if (!showServer) server = "ely.by"
                    }
                }

                app.loginError?.let { Txt(it, manrope(12f, color = MintColors.Danger)) }

                PrimaryButton(
                    if (app.loginBusy) "Входим…" else "Войти",
                    Modifier.fillMaxWidth(),
                    enabled = !app.loginBusy && login.isNotBlank() && password.isNotBlank(),
                    onClick = submit,
                )
                OutlineButton(
                    "Играть офлайн с этим ником",
                    Modifier.fillMaxWidth(),
                    enabled = !app.loginBusy && login.isNotBlank(),
                    leading = { Icon(MintIcon.User, 16.dp, MintColors.ink(0.6f)) },
                ) { app.playOffline(login) }
            }

            Spacer()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LinkText("Регистрация на Ely.by", manrope(12.5f, color = MintColors.ink(0.78f))) {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://account.ely.by/register")) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, manrope(11.5f, FontWeight.SemiBold, MintColors.ink(0.85f)))
        content()
    }
}
