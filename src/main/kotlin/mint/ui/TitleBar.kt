package mint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

/** Действия с окном, передаются из Main. */
class WindowActions(
    val state: WindowState,
    val minimize: () -> Unit,
    val close: () -> Unit,
) {
    fun toggleMaximize() {
        state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
}

@Composable
fun WindowButton(icon: MintIcon, iconSize: Int, tint: Color, danger: Boolean = false, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Box(
        Modifier.size(34.dp, 30.dp).clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    hovered && danger -> MintColors.CloseHover
                    hovered -> MintColors.ink(0.07f)
                    else -> Color.Transparent
                }
            )
            .clickableNoRipple(source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, iconSize.dp, tint) }
}

@Composable
fun WindowControls(actions: WindowActions, maximizable: Boolean = true, tint: Color = MintColors.ink(0.78f)) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        WindowButton(MintIcon.Minus, 13, tint, onClick = actions.minimize)
        if (maximizable) WindowButton(MintIcon.Square, 11, tint, onClick = actions::toggleMaximize)
        WindowButton(MintIcon.X, 13, tint, danger = true, onClick = actions.close)
    }
}

/** Заголовок 46dp: логотип, слот контента, кнопки окна. Вся полоса перетаскивает окно. */
@Composable
fun FrameWindowScope.TitleBar(
    actions: WindowActions,
    content: @Composable RowScope.() -> Unit = {},
) {
    WindowDraggableArea {
        Column {
            Row(
                Modifier.fillMaxWidth().height(46.dp)
                    .background(MintColors.Window.copy(alpha = 0.92f))
                    .padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Logo(24.dp, 8.dp, 13f)
                Txt("Mint", nunito(15f))
                content()
                WindowControls(actions)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MintColors.ink(0.07f)))
        }
    }
}
