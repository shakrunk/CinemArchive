package work.kumarfamilynet.cinemarchive.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.time.Instant
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.seating

/**
 * The "at the theater" screen, reached from Up Next's marquee card (GitHub #221).
 *
 * Deliberately **two modes rather than one screen**, because the two things you do at a
 * cinema want opposite screens and happen minutes apart:
 *
 *  - [TicketMode.Scan] at the door — the code has to be readable by a handheld scanner, so
 *    the panel is forced white with a black code at maximum backlight, whatever theme the
 *    app is wearing.
 *  - [TicketMode.Auditorium] walking in — the auditorium number has to be readable by *you*,
 *    in a dark room, without lighting up the three rows behind you. Black field, dim amber
 *    type, backlight pinned near its floor.
 *
 * Showing both at once (the original single-column layout) meant one of them was always
 * wrong. The mode starts on whichever matches the clock and is switchable by hand, since
 * "I got waved through without scanning" and "I need to show it again" both happen.
 *
 * Reuses existing [CinemaOuting] fields: the structured auditorium/row/seats trio for the seat
 * block. For the code, [CinemaOuting.ticketImagePath] (a captured photo of the real ticket —
 * GitHub #219) is preferred when present, since re-encoding [CinemaOuting.bookingRef] as a QR
 * is not guaranteed to be scannable — `bookingRef` is a *booking confirmation code*, not
 * necessarily the vendor's actual barcode payload, and even when it is, a non-QR symbology
 * (e.g. `CODE_128`) re-drawn as a QR produces a code no turnstile scanner will read. The
 * bookingRef-as-QR fallback only applies once nothing has been captured.
 */
@Composable
fun TicketScreen(titleName: String, outing: CinemaOuting, onBack: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(initialMode(outing).name) }
    val current = TicketMode.valueOf(mode)

    // Held across both modes: nothing here is worth a screen timeout, at the door or in the
    // aisle. Brightness is per-mode; staying awake is not.
    KeepScreenOn()

    when (current) {
        TicketMode.Scan -> ScanMode(
            titleName = titleName,
            outing = outing,
            onBack = onBack,
            onFindSeat = { mode = TicketMode.Auditorium.name },
        )
        TicketMode.Auditorium -> AuditoriumMode(
            outing = outing,
            onBack = onBack,
            onShowCode = { mode = TicketMode.Scan.name },
        )
    }
}

enum class TicketMode { Scan, Auditorium }

/** Before the film starts you're still outside the auditorium; after it starts you're in a
 *  dark room and the code has already been scanned. A malformed showtime falls back to
 *  [TicketMode.Scan] — the recoverable direction, since one tap reaches the other mode and
 *  a wrongly-dimmed screen at the door is the more annoying failure. */
internal fun initialMode(outing: CinemaOuting, now: Instant = Instant.now()): TicketMode {
    val showtime = runCatching { Instant.parse(outing.showtime) }.getOrNull() ?: return TicketMode.Scan
    return if (now.isBefore(showtime)) TicketMode.Scan else TicketMode.Auditorium
}

// ─── Scan mode ──────────────────────────────────────────────────────────────

@Composable
private fun ScanMode(titleName: String, outing: CinemaOuting, onBack: () -> Unit, onFindSeat: () -> Unit) {
    ScreenBrightnessOverride(1f)

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
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            val capturedTicketPath = outing.ticketImagePath
            if (capturedTicketPath != null) {
                CapturedTicketCard(capturedTicketPath)
            } else {
                TicketCodeCard(outing.bookingRef)
            }

            TextButton(onClick = onFindSeat) {
                Icon(Icons.Filled.EventSeat, contentDescription = null)
                Text("Scanned — find my seat", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Renders the actual captured ticket photo (GitHub #219) rather than a re-encoded code — the
 *  real proof-of-ticket, whatever symbology or layout the vendor printed it in. Same white
 *  panel as [TicketCodeCard] for the same reason (scanners read dark-on-light, and this is the
 *  one place the app deliberately ignores its own theme). */
@Composable
private fun CapturedTicketCard(imagePath: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(16.dp),
    ) {
        Text("YOUR TICKET", style = MaterialTheme.typography.labelSmall, color = Color.Black.copy(alpha = 0.55f))
        AsyncImage(
            model = File(imagePath),
            contentDescription = "Captured ticket photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.padding(top = 12.dp).widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
        )
    }
}

/** The code panel is unconditionally white-on-black-modules, ignoring the theme. Scanners
 *  read dark-on-light; an inverted code (light modules on a dark field, which is what every
 *  dark palette here produces) is unreadable to most of them. This card is the one place in
 *  the app that deliberately breaks theming, so the panel is drawn white rather than any
 *  `surface` token. */
@Composable
private fun TicketCodeCard(bookingRef: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (bookingRef != null) Color.White else MaterialTheme.colorScheme.surfaceContainer)
            .padding(24.dp),
    ) {
        Text(
            "TICKET CODE",
            style = MaterialTheme.typography.labelSmall,
            color = if (bookingRef != null) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (bookingRef != null) {
            TicketQrCode(
                content = bookingRef,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
            )
            Text(
                bookingRef,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Text(
                "No booking ref saved — add one to generate a scannable code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Renders [content] as a QR code by hand-painting a [QRCodeWriter] bit matrix into a bitmap
 *  — no bitmap-conversion helper library needed for a plain two-tone code. Fixed black on
 *  white (see [TicketCodeCard]), with the quiet zone and error correction stated explicitly
 *  rather than left to the writer's defaults, since both matter to a real scanner. */
@Composable
fun TicketQrCode(content: String, modifier: Modifier = Modifier) {
    val darkColor = Color.Black.toArgb()
    val lightColor = Color.White.toArgb()
    val bitmap = remember(content) {
        val size = 512
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) darkColor else lightColor
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = "Ticket QR code", modifier = modifier)
}

// ─── Auditorium mode ────────────────────────────────────────────────────────

/** Warm, low-saturation amber on black: bright enough to read at 2% backlight, far enough
 *  from white that it doesn't wash out a dark-adapted eye or carry down the row. */
private val TheaterInk = Color(0xFFE0A458)
private val TheaterInkDim = Color(0xFF8A6A44)

/** The backlight floor. Not `0f` — that's "as dim as this panel goes", which on some
 *  displays is genuinely unreadable — but low enough to be unobtrusive in a dark room. */
private const val THEATER_BRIGHTNESS = 0.02f

@Composable
private fun AuditoriumMode(outing: CinemaOuting, onBack: () -> Unit, onShowCode: () -> Unit) {
    ScreenBrightnessOverride(THEATER_BRIGHTNESS)

    val seating = outing.seating
    val legacySeat = seating.line

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.iconButtonColors(contentColor = TheaterInkDim),
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
        ) {
            when {
                seating.isStructured -> {
                    // "Where you're sitting", not "Seats": the hero line is the auditorium,
                    // which is the thing you're actually hunting for in the hallway.
                    Eyebrow(if (seating.auditoriumLabel != null) "YOU'RE IN" else "YOU'RE SITTING IN")
                    seating.auditoriumLabel?.let { Hero(it) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        modifier = Modifier.padding(top = if (seating.auditoriumLabel != null) 32.dp else 8.dp),
                    ) {
                        seating.seatRow?.trim()?.takeIf { it.isNotEmpty() }?.let { Stat("ROW", it) }
                        seating.seats.map(String::trim).filter(String::isNotEmpty).takeIf { it.isNotEmpty() }?.let {
                            Stat(if (it.size == 1) "SEAT" else "SEATS", it.joinToString(", "))
                        }
                    }
                }
                legacySeat != null -> {
                    // Pre-#221 outing: one free-text string, shown verbatim. Splitting it
                    // into the layout above would mean guessing which part is which.
                    Eyebrow("YOUR SEAT")
                    Hero(legacySeat)
                }
                else -> {
                    Text(
                        "No seat saved yet — add one from Edit tickets.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TheaterInkDim,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        TextButton(
            onClick = onShowCode,
            colors = ButtonDefaults.textButtonColors(contentColor = TheaterInkDim),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp),
        ) {
            Icon(Icons.Filled.QrCode, contentDescription = null)
            Text("Show ticket code", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = TheaterInkDim, letterSpacing = 3.sp)
}

@Composable
private fun Hero(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.SemiBold,
        color = TheaterInk,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TheaterInkDim, letterSpacing = 2.sp)
        Text(
            value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium,
            color = TheaterInk,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ─── Window plumbing ────────────────────────────────────────────────────────

/** Pins the window's backlight to [level] for the lifetime of this composable, restoring
 *  whatever was set before on dispose. Keyed on [level] so switching modes re-runs it. */
@Composable
private fun ScreenBrightnessOverride(level: Float) {
    val context = LocalContext.current
    DisposableEffect(level) {
        val window = context.findActivity()?.window
        if (window == null) {
            onDispose {}
        } else {
            val previousBrightness = window.attributes.screenBrightness
            window.attributes = window.attributes.apply { screenBrightness = level }
            onDispose {
                window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
            }
        }
    }
}

/** Holds the screen awake while a ticket is on it — the display timing out mid-queue is the
 *  one failure this screen exists to prevent. */
@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
