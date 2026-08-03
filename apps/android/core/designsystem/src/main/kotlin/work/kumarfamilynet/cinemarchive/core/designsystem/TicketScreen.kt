package work.kumarfamilynet.cinemarchive.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting

/**
 * The "at the theater" screen — seat lookup and a scannable ticket code, reached from Up
 * Next's marquee card (GitHub #221). Stateless and callback-driven, same shape as
 * [PostShowSheet], so it isn't tied to one feature module. Deliberately reuses existing
 * [CinemaOuting] fields rather than adding new ones: [CinemaOuting.seat] for the seat block,
 * [CinemaOuting.bookingRef] for the QR code content — there's no dedicated ticket-scan/upload
 * field yet (that's GitHub #219, a separate piece of work).
 */
@Composable
fun TicketScreen(titleName: String, outing: CinemaOuting, onBack: () -> Unit) {
    ScreenBrightnessBoost()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
            }
            Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                Text(titleName, style = MaterialTheme.typography.titleLarge)
                val subtitle = listOfNotNull(
                    outing.venue,
                    outing.companions.takeIf { it.isNotEmpty() }?.let { "with ${it.joinToString(" & ")}" },
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            SeatCard(outing.seat)
            TicketCodeCard(outing.bookingRef)
        }
    }
}

@Composable
private fun SeatCard(seat: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(24.dp),
    ) {
        Text("SEAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (seat != null) {
            Text(
                seat,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                "No seat saved yet — add one from Edit tickets.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TicketCodeCard(bookingRef: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(24.dp),
    ) {
        Text("TICKET CODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (bookingRef != null) {
            TicketQrCode(content = bookingRef, modifier = Modifier.padding(top = 12.dp).size(220.dp))
        } else {
            Text(
                "No booking ref saved — add one to generate a scannable code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Renders [content] as a QR code by hand-painting a [QRCodeWriter] bit matrix into a bitmap —
 *  no bitmap-conversion helper library needed for a plain two-tone code. Colors come from the
 *  current [MaterialTheme.colorScheme] rather than plain black/white so the code still reads
 *  correctly against the Noir/Matrix palettes, not just the default Brand one. */
@Composable
fun TicketQrCode(content: String, modifier: Modifier = Modifier) {
    val darkColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val lightColor = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val bitmap = remember(content, darkColor, lightColor) {
        val size = 512
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) darkColor else lightColor)
            }
        }
        bmp.asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = "Ticket QR code", modifier = modifier)
}

/** Pushes the window's screen brightness to max for the lifetime of this composable so a
 *  ticket QR code is easy for a theater scanner to read, restoring whatever brightness was set
 *  before on dispose. First screen in the app to touch brightness — kept local here rather
 *  than promoted to a shared utility until a second caller needs it. */
@Composable
private fun ScreenBrightnessBoost() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            val previousBrightness = window.attributes.screenBrightness
            window.attributes = window.attributes.apply { screenBrightness = 1f }
            onDispose {
                window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
