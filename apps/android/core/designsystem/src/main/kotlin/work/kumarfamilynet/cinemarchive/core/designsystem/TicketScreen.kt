package work.kumarfamilynet.cinemarchive.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import work.kumarfamilynet.cinemarchive.core.model.CinemaOuting
import work.kumarfamilynet.cinemarchive.core.model.seating

/** Bright scan mode and dim auditorium mode share the outing's real seat assignment.
 * Private originals and actual decoded codes are supplied by the account-scoped ticket route. */
@Composable
fun TicketScreen(titleName: String, outing: CinemaOuting, onBack: () -> Unit, ticketContent: (@Composable () -> Unit)? = null) {
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
            ticketContent = ticketContent,
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
private fun ScanMode(titleName: String, outing: CinemaOuting, onBack: () -> Unit, onFindSeat: () -> Unit, ticketContent: (@Composable () -> Unit)?) {
    ScreenBrightnessOverride(1f)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
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
            if (ticketContent != null) ticketContent()
            else Text("Open the private ticket viewer to show an original photo. Booking references are not barcodes.")

            TextButton(onClick = onFindSeat) {
                Icon(Icons.Filled.EventSeat, contentDescription = null)
                Text("Scanned — find my seat", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
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
