package mint.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mint.game.Instance
import mint.game.Loader
import mint.game.Packs
import mint.game.pluralMods

/** Вкладка «Сборки»: знакомство со сборками и выбор активной. */
@Composable
fun InstancesTab(app: AppState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header("Сборки", "Выберите сборку — «Играть» на главной запустит именно её")
        app.instances.forEach { PackCard(app, it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackCard(app: AppState, instance: Instance) {
    val info = Packs.info(instance.id)
    val selected = instance.id == app.selectedInstance.id
    val shape = RoundedCornerShape(18.dp)

    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(MintColors.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) MintColors.MintBorder else MintColors.ink(0.07f), shape)
    ) {
        Cover(instance, info?.tagline.orEmpty(), selected)

        Column(Modifier.padding(horizontal = 26.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Minecraft ${instance.minecraft}")
                Chip(
                    when (instance.loader) {
                        Loader.NEOFORGE -> "NeoForge" + instance.loaderVersion.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                        Loader.VANILLA -> "Vanilla"
                    }
                )
                if (instance.installed) {
                    if (instance.loader != Loader.VANILLA) Chip(pluralMods(instance.modCount))
                    Chip("Установлена", accent = true)
                } else {
                    Chip("Скачается при первом запуске")
                }
            }

            val paragraphs = info?.description?.ifEmpty { null }
                ?: listOf("Локальная сборка: лежит в папке instances/${instance.id} и не обновляется из репозитория.")
            paragraphs.forEach {
                Txt(it, manrope(13f, FontWeight.Normal, MintColors.ink(0.82f), lineHeight = 20.sp))
            }

            if (!info?.highlights.isNullOrEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    info!!.highlights.forEach { Highlight(it) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer()
                if (selected) {
                    OutlineButton(
                        "Выбрана", height = 52.dp, radius = 15.dp, enabled = false,
                        textStyle = nunito(17f, color = MintColors.MintDeep),
                        leading = { Icon(MintIcon.Check, 17.dp, MintColors.MintDeep) },
                    ) {}
                } else {
                    PrimaryButton(
                        "Выбрать", Modifier.widthIn(min = 170.dp), height = 52.dp, radius = 15.dp,
                        textStyle = nunito(18f, color = MintColors.MintInk),
                    ) {
                        app.selectInstance(instance)
                        app.tab = Tab.Home
                    }
                }
            }
        }
    }
}

/** Превью сборки с иконкой и названием поверх затемнения — как hero на главной. */
@Composable
private fun Cover(instance: Instance, tagline: String, selected: Boolean) {
    val art = PackArt.banner(instance)
    val onArt = art != null
    Box(
        Modifier.fillMaxWidth().height(250.dp)
            .background(Brush.linearGradient(listOf(MintColors.HeroTop, MintColors.HeroBottom), start = Offset(0f, 0f), end = Offset(600f, 1400f)))
    ) {
        if (art != null) {
            Image(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to MintColors.ArtScrim.copy(alpha = 0.25f),
                        1f to MintColors.ArtScrim,
                    )
                )
            )
        } else {
            DiagonalStripes(Modifier.fillMaxSize())
        }

        if (selected) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(9.dp))
                    .background(MintColors.Mint).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(MintIcon.Check, 12.dp, MintColors.MintDarker)
                Txt("Активная", manrope(11.5f, FontWeight.Bold, MintColors.MintInk))
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 26.dp, end = 26.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PackIcon(instance, 64.dp, 16.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(instance.name, nunito(30f, color = if (onArt) MintColors.OnArt else MintColors.InkStrong, lineHeight = 34.sp), maxLines = 1)
                if (tagline.isNotBlank()) {
                    Txt(tagline, manrope(13f, FontWeight.Normal, if (onArt) MintColors.onArt(0.85f) else MintColors.ink(0.78f)), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, accent: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (accent) MintColors.Mint.copy(alpha = 0.35f) else MintColors.ink(0.05f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Txt(text, manrope(11.5f, FontWeight.SemiBold, if (accent) MintColors.MintDeep else MintColors.ink(0.75f)))
    }
}

@Composable
private fun Highlight(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(MintColors.Mint))
        Txt(text, manrope(12.5f, FontWeight.SemiBold, MintColors.ink(0.85f)))
    }
}
