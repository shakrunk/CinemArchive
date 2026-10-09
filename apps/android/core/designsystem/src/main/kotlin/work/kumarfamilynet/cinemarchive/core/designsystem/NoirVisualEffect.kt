package work.kumarfamilynet.cinemarchive.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.cos
import kotlin.math.sin

/** Content filter, independent of the selected app palette; applies to artwork as well as controls. */
fun Modifier.noirVisualEffect(mode: String?): Modifier {
    if (mode != "bw" && mode != "color") return this
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(noirColorMatrix(mode))) }
    return drawWithContent {
        drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, size.height), paint) }
        drawContent()
        drawIntoCanvas { it.restore() }
    }
}

/** Web: grayscale + brightness(1.06), or contrast(1.05) saturate(2.2) hue-rotate(-8deg). */
fun noirColorMatrix(mode: String): FloatArray {
    fun multiply(a: FloatArray, b: FloatArray) = FloatArray(20) { index ->
        val row = index / 5; val col = index % 5
        (0..3).sumOf { k -> (a[row * 5 + k] * b[k * 5 + col]).toDouble() }.toFloat() + if (col == 4) a[row * 5 + 4] else 0f
    }
    if (mode == "bw") return floatArrayOf(
        .2126f * 1.06f, .7152f * 1.06f, .0722f * 1.06f, 0f, 0f,
        .2126f * 1.06f, .7152f * 1.06f, .0722f * 1.06f, 0f, 0f,
        .2126f * 1.06f, .7152f * 1.06f, .0722f * 1.06f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)
    require(mode == "color")
    val s = 2.2f; val c = cos(Math.toRadians(-8.0)).toFloat(); val h = sin(Math.toRadians(-8.0)).toFloat()
    val saturation = floatArrayOf(
        .213f+.787f*s, .715f-.715f*s, .072f-.072f*s, 0f, 0f,
        .213f-.213f*s, .715f+.285f*s, .072f-.072f*s, 0f, 0f,
        .213f-.213f*s, .715f-.715f*s, .072f+.928f*s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)
    val hue = floatArrayOf(
        .213f+.787f*c-.213f*h, .715f-.715f*c-.715f*h, .072f-.072f*c+.928f*h, 0f, 0f,
        .213f-.213f*c+.143f*h, .715f+.285f*c+.140f*h, .072f-.072f*c-.283f*h, 0f, 0f,
        .213f-.213f*c-.787f*h, .715f-.715f*c+.715f*h, .072f+.928f*c+.072f*h, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)
    val contrast = floatArrayOf(1.05f,0f,0f,0f,-6.375f, 0f,1.05f,0f,0f,-6.375f, 0f,0f,1.05f,0f,-6.375f, 0f,0f,0f,1f,0f)
    return multiply(hue, multiply(saturation, contrast))
}
