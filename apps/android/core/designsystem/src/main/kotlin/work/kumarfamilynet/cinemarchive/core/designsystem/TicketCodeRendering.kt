package work.kumarfamilynet.cinemarchive.core.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcode
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat

/** Decode a bounded preview on IO; the saved original remains byte-for-byte unchanged. */
fun decodeTicketFile(file: File): TicketBarcode? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth in 1..65536 && bounds.outHeight in 1..65536) { "The original is not a supported image." }
    var sample = 1
    while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 4_000_000) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Could not decode the ticket image.")
    return try { decodeTicketBarcode(bitmap)?.let { TicketBarcode(it.first, it.second) } } finally { bitmap.recycle() }
}

/** Preserve the actual symbology. Text that cannot round-trip exactly has only the photo fallback. */
fun renderTicketBarcode(barcode: TicketBarcode): Bitmap? {
    if (barcode.format == TicketBarcodeFormat.OTHER || barcode.payload.isEmpty() || barcode.payload.any { it == '\uFFFD' || it.code < 32 || it.code == 127 }) return null
    val format = runCatching { BarcodeFormat.valueOf(barcode.format.name) }.getOrNull() ?: return null
    val linear = format in setOf(BarcodeFormat.CODE_128, BarcodeFormat.ITF, BarcodeFormat.CODABAR)
    val width = 1000
    val height = if (linear) 260 else 800
    return runCatching {
        val matrix = MultiFormatWriter().encode(barcode.payload, format, width, height,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 16))
        val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))),
            mapOf(DecodeHintType.RETURN_CODABAR_START_END to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
        require(decoded.barcodeFormat == format && decoded.text == barcode.payload)
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}

@Composable
fun TicketBarcodeImage(barcode: TicketBarcode) {
    val bitmap by produceState<Bitmap?>(null, barcode) { value = withContext(Dispatchers.Default) { renderTicketBarcode(barcode) } }
    val image = bitmap
    if (image == null) Text("This code cannot be reproduced exactly. Show the original ticket photo.")
    else Image(image.asImageBitmap(), contentDescription = "Actual ${barcode.format.name} ticket code",
        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).background(Color.White))
}
