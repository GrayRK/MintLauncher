package mint.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mint.core.AccountType

/** Вкладка «Аккаунт»: профиль, скин в 3D и выход. */
@Composable
fun AccountTab(app: AppState) {
    val account = app.account ?: return
    val skin = app.skin
    Column(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Header("Аккаунт")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier.width(300.dp).fillMaxHeight().clip(shape)
                    .background(Brush.linearGradient(listOf(MintColors.HeroTop, MintColors.HeroBottom), start = Offset(0f, 0f), end = Offset(300f, 900f)))
                    .border(1.dp, MintColors.ink(0.07f), shape),
                contentAlignment = Alignment.Center,
            ) {
                if (skin != null) {
                    SkinModelView(skin, Modifier.fillMaxSize().padding(vertical = 28.dp, horizontal = 20.dp))
                    Icon(MintIcon.Rotate3d, 30.dp, MintColors.ink(0.45f), Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp))
                } else {
                    Txt(if (app.skinLoading) "Скин загружается…" else "Скин недоступен", manrope(12f, color = MintColors.ink(0.55f)))
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SkinHead(skin, 44.dp, 12.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Txt(account.username, manrope(15f, FontWeight.SemiBold))
                            Txt(
                                when (account.type) {
                                    AccountType.YGGDRASIL -> "Авторизация: ${account.authServer}"
                                    AccountType.OFFLINE -> "Офлайн-режим · скины и лицензионные серверы недоступны"
                                },
                                manrope(11.5f, FontWeight.Normal, MintColors.ink(0.78f)),
                            )
                        }
                        OutlineButton("Выйти", height = 40.dp, radius = 11.dp, leading = { Icon(MintIcon.SignOut, 15.dp, MintColors.ink(0.6f)) }) {
                            app.logout()
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), spacing = 10.dp) {
                    InfoRow("Тип", if (account.type == AccountType.YGGDRASIL) "Yggdrasil (${account.authServer})" else "Офлайн")
                    InfoRow("UUID", account.uuid)
                    InfoRow(
                        "Модель",
                        when {
                            account.type == AccountType.OFFLINE -> "стандартный скин"
                            skin == null -> "—"
                            skin.slim -> "тонкие руки (Alex)"
                            else -> "классическая (Steve)"
                        },
                    )
                }
                if (account.type == AccountType.YGGDRASIL) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlineButton("Обновить скин", height = 40.dp, radius = 11.dp, leading = { Icon(MintIcon.Refresh, 15.dp, MintColors.ink(0.6f)) }) {
                            app.reloadSkin()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Txt(label, manrope(12.5f, color = MintColors.ink(0.7f)), Modifier.width(90.dp))
        Txt(value, manrope(12.5f, FontWeight.SemiBold), maxLines = 1)
    }
}
