package mint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import mint.core.AccountType

/** Каркас после входа: заголовок, боковая панель вкладок и содержимое выбранной вкладки. */
@Composable
fun FrameWindowScope.MainScreen(app: AppState, actions: WindowActions) {
    Column(Modifier.fillMaxSize()) {
        TitleBar(actions) {
            Spacer()
            AccountChip(app)
        }
        Row(Modifier.fillMaxSize()) {
            SideDock(app)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (app.tab) {
                    Tab.Home, Tab.Instances, Tab.Mods -> HomeTab(app)
                    Tab.Server -> ServerTab(app)
                    Tab.Account -> AccountTab(app)
                    Tab.Settings -> SettingsTab(app)
                }
            }
        }
    }
}

@Composable
private fun AccountChip(app: AppState) {
    val account = app.account ?: return
    val (source, hovered) = rememberHover()
    Row(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (hovered || app.tab == Tab.Account) MintColors.ink(0.06f) else Color.Transparent)
            .clickableNoRipple(source) { app.tab = Tab.Account }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SkinHead(app.skin, 22.dp, 6.dp)
        Txt(account.username, manrope(12f, FontWeight.SemiBold))
        if (account.type == AccountType.OFFLINE) Txt("офлайн", manrope(10.5f, color = MintColors.ink(0.5f)))
    }
    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
}

@Composable
private fun SideDock(app: AppState) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(68.dp).fillMaxHeight().background(MintColors.Sand.copy(alpha = 0.5f)).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DockButton(MintIcon.House, app.tab == Tab.Home) { app.tab = Tab.Home }
            // Сборки и моды появятся позже
            DockButton(MintIcon.Cube, app.tab == Tab.Instances) { }
            DockButton(MintIcon.Puzzle, app.tab == Tab.Mods) { }
            // Сервер есть в доке, только пока он запущен: выключенному серверу там делать нечего
            if (app.serverTabVisible) DockButton(MintIcon.Server, app.tab == Tab.Server) { app.tab = Tab.Server }
            DockButton(MintIcon.UserCircle, app.tab == Tab.Account) { app.tab = Tab.Account }
            Spacer()
            DockButton(MintIcon.Gear, app.tab == Tab.Settings) { app.tab = Tab.Settings }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MintColors.ink(0.06f)))
    }
}

@Composable
private fun DockButton(icon: MintIcon, active: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    active -> MintColors.Mint
                    hovered -> MintColors.Mint.copy(alpha = 0.3f)
                    else -> Color.Transparent
                }
            )
            .clickableNoRipple(source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, 19.dp,
            when {
                active -> MintColors.MintDarker
                hovered -> MintColors.MintDeep
                else -> MintColors.ink(0.45f)
            },
        )
    }
}
