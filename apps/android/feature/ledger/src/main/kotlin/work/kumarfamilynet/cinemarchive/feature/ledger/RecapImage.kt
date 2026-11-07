package work.kumarfamilynet.cinemarchive.feature.ledger

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingRecap
import work.kumarfamilynet.cinemarchive.core.model.MoviegoingRecapText
import java.io.File

private const val WIDTH = 1080
private const val HEIGHT = 1350
private const val MARGIN = 90f

// The image has to look the same whatever theme the sharer runs, so it uses the app's dark-gold
// palette directly (CinemArchiveTheme) rather than the active colour scheme.
private const val BACKGROUND = 0xFF0B0907.toInt()
private const val GOLD = 0xFFE9B266.toInt()
private const val INK = 0xFFF3EAD9.toInt()
private const val MUTED = 0xFFB5A58C.toInt()

/** Draws [recap] as a share-ready card; the lines come from [MoviegoingRecapText], like the
 *  on-screen card, so the image never disagrees with it. */
internal fun renderRecapBitmap(recap: MoviegoingRecap): Bitmap {
    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(BACKGROUND)

    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD; alpha = 140; style = Paint.Style.STROKE; strokeWidth = 4f }
    canvas.drawRoundRect(40f, 40f, WIDTH - 40f, HEIGHT - 40f, 36f, 36f, border)

    fun paint(sizePx: Float, argb: Int, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx; color = argb; typeface = face; letterSpacing = spacing }

    val maxText = WIDTH - 2 * MARGIN
    fun text(value: String, y: Float, p: TextPaint) {
        canvas.drawText(TextUtils.ellipsize(value, p, maxText, TextUtils.TruncateAt.END).toString(), MARGIN, y, p)
    }

    text("CINEMARCHIVE", 170f, paint(34f, MUTED, spacing = 0.3f))
    text(recap.year.toString(), 360f, paint(220f, GOLD, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)))
    text("AT THE MOVIES", 440f, paint(40f, INK, spacing = 0.2f))
    text(recap.tripCount.toString(), 680f, paint(240f, INK, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)))
    text(if (recap.tripCount == 1) "trip" else "trips", 750f, paint(56f, MUTED))

    var y = 870f
    MoviegoingRecapText.fields(recap).forEach { field ->
        text(field.label.uppercase(), y, paint(32f, MUTED, spacing = 0.2f))
        text(field.value, y + 66f, paint(60f, INK))
        y += 140f
    }
    return bitmap
}

/** Renders the recap to the app cache and hands it to the system share sheet with a one-line
 *  text summary. Nothing leaves the device until the person picks a target. */
internal fun shareRecap(context: Context, recap: MoviegoingRecap) {
    val dir = File(context.cacheDir, "recap").apply { mkdirs() }
    val file = File(dir, "moviegoing-${recap.year}.png")
    renderRecapBitmap(recap).use { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.tickets", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, MoviegoingRecapText.summary(recap))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    context.startActivity(Intent.createChooser(send, "Share your year at the movies"))
}

private inline fun <R> Bitmap.use(block: (Bitmap) -> R): R = try { block(this) } finally { recycle() }
