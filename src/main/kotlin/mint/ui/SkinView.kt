package mint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import mint.auth.Skin
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Голова игрока: лицо 8×8 и слой шляпы поверх, без сглаживания. */
@Composable
fun SkinHead(skin: Skin?, size: Dp, radius: Dp) {
    val shape = RoundedCornerShape(radius)
    if (skin == null) {
        Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(MintColors.SandDark, MintColors.Sand))))
        return
    }
    val head = remember(skin) { headBitmap(skin) }
    Image(
        head, contentDescription = null,
        modifier = Modifier.size(size).clip(shape),
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
    )
}

private fun headBitmap(skin: Skin): ImageBitmap {
    val k = skin.image.width / 64
    val out = BufferedImage(8 * k, 8 * k, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 8 * k) for (x in 0 until 8 * k) {
        val face = skin.image.getRGB(8 * k + x, 8 * k + y) or (0xFF shl 24)
        val hat = skin.image.getRGB(40 * k + x, 8 * k + y)
        out.setRGB(x, y, if (hat ushr 24 > 0x80) hat else face)
    }
    return out.toComposeImageBitmap()
}

/** Полная модель игрока, вращается перетаскиванием мыши. */
@Composable
fun SkinModelView(skin: Skin, modifier: Modifier = Modifier) {
    var yaw by remember { mutableFloatStateOf(-0.5f) }
    var pitch by remember { mutableFloatStateOf(0.12f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val model = remember(skin) { PlayerModel(skin) }
    val frame = remember(model, size, yaw, pitch) {
        if (size.width > 0 && size.height > 0) model.render(size.width, size.height, yaw, pitch) else null
    }
    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    yaw += drag.x * 0.012f
                    pitch = (pitch + drag.y * 0.008f).coerceIn(-0.6f, 0.6f)
                }
            }
    ) {
        frame?.let { drawImage(it, dstOffset = IntOffset.Zero, filterQuality = FilterQuality.None) }
    }
}

/**
 * Программный растеризатор с z-буфером: 6 деталей по 2 слоя, каждая грань —
 * параллелограмм в ортографической проекции, текстура выбирается ближайшим соседом.
 */
private class PlayerModel(skin: Skin) {
    private class Face(
        val o: DoubleArray, val u: DoubleArray, val v: DoubleArray, val n: DoubleArray,
        val tx: Int, val ty: Int, val tw: Int, val th: Int, val overlay: Boolean,
    )

    private val tex: IntArray
    private val texW: Int
    private val k: Int
    private val faces = ArrayList<Face>()

    init {
        val img = skin.image
        texW = img.width
        k = img.width / 64
        tex = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val arm = if (skin.slim) 3.0 else 4.0
        val legacy = skin.legacy

        // Координаты в пикселях скина: y вверх, z к зрителю, у игрока правая сторона — это -x
        box(-4.0, 24.0, -4.0, 8.0, 8.0, 8.0, 0, 0, 0.0)          // голова
        box(-4.0, 24.0, -4.0, 8.0, 8.0, 8.0, 32, 0, 0.5)         // шляпа
        box(-4.0, 12.0, -2.0, 8.0, 12.0, 4.0, 16, 16, 0.0)       // тело
        box(-4.0 - arm, 12.0, -2.0, arm, 12.0, 4.0, 40, 16, 0.0) // правая рука
        box(4.0, 12.0, -2.0, arm, 12.0, 4.0, if (legacy) 40 else 32, if (legacy) 16 else 48, 0.0)
        box(-4.0, 0.0, -2.0, 4.0, 12.0, 4.0, 0, 16, 0.0)         // правая нога
        box(0.0, 0.0, -2.0, 4.0, 12.0, 4.0, if (legacy) 0 else 16, if (legacy) 16 else 48, 0.0)
        if (!legacy) {
            box(-4.0, 12.0, -2.0, 8.0, 12.0, 4.0, 16, 32, 0.25)
            box(-4.0 - arm, 12.0, -2.0, arm, 12.0, 4.0, 40, 32, 0.25)
            box(4.0, 12.0, -2.0, arm, 12.0, 4.0, 48, 48, 0.25)
            box(-4.0, 0.0, -2.0, 4.0, 12.0, 4.0, 0, 32, 0.25)
            box(0.0, 0.0, -2.0, 4.0, 12.0, 4.0, 0, 48, 0.25)
        }
    }

    /** Коробка с минимальным углом (x, y, z), размерами w×h×d и разметкой текстуры от (tu, tv). */
    private fun box(x: Double, y: Double, z: Double, w: Double, h: Double, d: Double, tu: Int, tv: Int, inflate: Double) {
        val x0 = x - inflate; val y0 = y - inflate; val z0 = z - inflate
        val x1 = x + w + inflate; val y1 = y + h + inflate; val z1 = z + d + inflate
        val W = x1 - x0; val H = y1 - y0; val D = z1 - z0
        val iw = w.toInt(); val ih = h.toInt(); val id = d.toInt()
        val overlay = inflate > 0
        fun face(o: DoubleArray, u: DoubleArray, v: DoubleArray, n: DoubleArray, px: Int, py: Int, pw: Int, ph: Int) {
            faces += Face(o, u, v, n, px, py, pw, ph, overlay)
        }
        val down = doubleArrayOf(0.0, -H, 0.0)
        face(doubleArrayOf(x0, y1, z1), doubleArrayOf(W, 0.0, 0.0), down, doubleArrayOf(0.0, 0.0, 1.0), tu + id, tv + id, iw, ih)             // перед
        face(doubleArrayOf(x1, y1, z0), doubleArrayOf(-W, 0.0, 0.0), down, doubleArrayOf(0.0, 0.0, -1.0), tu + 2 * id + iw, tv + id, iw, ih) // спина
        face(doubleArrayOf(x0, y1, z0), doubleArrayOf(0.0, 0.0, D), down, doubleArrayOf(-1.0, 0.0, 0.0), tu, tv + id, id, ih)                // правый бок
        face(doubleArrayOf(x1, y1, z1), doubleArrayOf(0.0, 0.0, -D), down, doubleArrayOf(1.0, 0.0, 0.0), tu + id + iw, tv + id, id, ih)      // левый бок
        face(doubleArrayOf(x0, y1, z0), doubleArrayOf(W, 0.0, 0.0), doubleArrayOf(0.0, 0.0, D), doubleArrayOf(0.0, 1.0, 0.0), tu + id, tv, iw, id)       // верх
        face(doubleArrayOf(x0, y0, z1), doubleArrayOf(W, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -D), doubleArrayOf(0.0, -1.0, 0.0), tu + id + iw, tv, iw, id) // низ
    }

    fun render(width: Int, height: Int, yaw: Float, pitch: Float): ImageBitmap {
        val cy = cos(yaw.toDouble()); val sy = sin(yaw.toDouble())
        val cp = cos(pitch.toDouble()); val sp = sin(pitch.toDouble())
        // Поворот вокруг Y, затем вокруг X; результат: (экранный x, y вверх, глубина к зрителю)
        fun rot(p: DoubleArray, out: DoubleArray) {
            val x1 = p[0] * cy + p[2] * sy
            val z1 = -p[0] * sy + p[2] * cy
            out[0] = x1
            out[1] = p[1] * cp - z1 * sp
            out[2] = p[1] * sp + z1 * cp
        }

        val scale = min(width / 22.0, height / 36.0)
        val cx = width / 2.0
        val cyScreen = height / 2.0
        val centerY = 16.0

        val pixels = IntArray(width * height)
        val depth = DoubleArray(width * height) { Double.NEGATIVE_INFINITY }
        val o = DoubleArray(3); val u = DoubleArray(3); val v = DoubleArray(3); val n = DoubleArray(3)
        val shifted = DoubleArray(3)

        for (f in faces) {
            rot(f.n, n)
            if (!f.overlay && n[2] <= 1e-6) continue
            shifted[0] = f.o[0]; shifted[1] = f.o[1] - centerY; shifted[2] = f.o[2]
            rot(shifted, o); rot(f.u, u); rot(f.v, v)

            val ox = cx + o[0] * scale; val oy = cyScreen - o[1] * scale
            val ux = u[0] * scale; val uy = -u[1] * scale
            val vx = v[0] * scale; val vy = -v[1] * scale
            val det = ux * vy - uy * vx
            if (abs(det) < 1e-6) continue

            val minX = max(0, floor(min(ox, min(ox + ux, min(ox + vx, ox + ux + vx)))).toInt())
            val maxX = min(width - 1, floor(max(ox, max(ox + ux, max(ox + vx, ox + ux + vx)))).toInt())
            val minY = max(0, floor(min(oy, min(oy + uy, min(oy + vy, oy + uy + vy)))).toInt())
            val maxY = min(height - 1, floor(max(oy, max(oy + uy, max(oy + vy, oy + uy + vy)))).toInt())

            // Мягкий свет сверху-спереди, как в инвентаре игры
            val light = (0.62 + 0.23 * max(0.0, n[1]) + 0.3 * max(0.0, n[2]) - 0.08 * max(0.0, -n[1])).coerceIn(0.45, 1.0)
            val tw = f.tw * k; val th = f.th * k; val tx0 = f.tx * k; val ty0 = f.ty * k

            for (py in minY..maxY) {
                val dy = py + 0.5 - oy
                for (px in minX..maxX) {
                    val dx = px + 0.5 - ox
                    val s = (dx * vy - dy * vx) / det
                    if (s < 0 || s >= 1) continue
                    val t = (ux * dy - uy * dx) / det
                    if (t < 0 || t >= 1) continue
                    val z = o[2] + s * u[2] + t * v[2] + if (f.overlay) 0.01 else 0.0
                    val idx = py * width + px
                    if (z <= depth[idx]) continue
                    val c = tex[(ty0 + (t * th).toInt()) * texW + tx0 + (s * tw).toInt()]
                    val alpha = c ushr 24
                    if (f.overlay && alpha < 0x80) continue
                    depth[idx] = z
                    val r = ((c shr 16 and 0xFF) * light).toInt()
                    val g = ((c shr 8 and 0xFF) * light).toInt()
                    val b = ((c and 0xFF) * light).toInt()
                    pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, width, height, pixels, 0, width)
        return img.toComposeImageBitmap()
    }
}
