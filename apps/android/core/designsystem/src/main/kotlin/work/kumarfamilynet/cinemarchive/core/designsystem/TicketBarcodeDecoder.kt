package work.kumarfamilynet.cinemarchive.core.designsystem

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultMetadataType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat

/**
 * Decodes whatever barcode is printed on a captured ticket photo (GitHub #219) — tries every
 * symbology [MultiFormatReader] knows rather than assuming QR, since box-office printers use
 * whatever their vendor software defaults to (1D `CODE_128` is at least as common as a QR).
 * Reuses the existing `zxing-core` dependency for decoding — no new
 * scanning library needed.
 *
 * Returns null when nothing decodes (a blurry photo, glare, or a ticket with no printed code
 * at all) rather than throwing — the caller still keeps the photo itself as the visual
 * proof-of-ticket even without a payload.
 */
fun decodeTicketBarcode(bitmap: Bitmap): Pair<String, TicketBarcodeFormat>? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    val result = runCatching { MultiFormatReader().decode(binaryBitmap, mapOf(
        DecodeHintType.RETURN_CODABAR_START_END to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )) }.getOrNull() ?: return null
    // Replacement characters, GS1/control payloads and opaque binary byte segments cannot
    // safely be reconstructed from plain text metadata. Their unchanged photo is the fallback.
    if (result.text.any { it == '\uFFFD' || it.code < 32 || it.code == 127 }) return null
    val identifier = result.resultMetadata?.get(ResultMetadataType.SYMBOLOGY_IDENTIFIER)?.toString()
    if (identifier in setOf("]C1", "]Q3", "]Q5", "]Q4", "]Q6")) return null
    val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? Iterable<*>
    if (segments != null) {
        val bytes = segments.map { it as? ByteArray ?: return null }.fold(byteArrayOf()) { all, next -> all + next }
        if (!bytes.contentEquals(result.text.toByteArray(Charsets.UTF_8))) return null
    }
    return result.text to result.barcodeFormat.toTicketBarcodeFormat()
}

private fun BarcodeFormat.toTicketBarcodeFormat(): TicketBarcodeFormat = when (this) {
    BarcodeFormat.QR_CODE -> TicketBarcodeFormat.QR_CODE
    BarcodeFormat.CODE_128 -> TicketBarcodeFormat.CODE_128
    BarcodeFormat.PDF_417 -> TicketBarcodeFormat.PDF_417
    BarcodeFormat.AZTEC -> TicketBarcodeFormat.AZTEC
    BarcodeFormat.ITF -> TicketBarcodeFormat.ITF
    BarcodeFormat.CODABAR -> TicketBarcodeFormat.CODABAR
    else -> TicketBarcodeFormat.OTHER
}
