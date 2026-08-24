package work.kumarfamilynet.cinemarchive.core.designsystem

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import work.kumarfamilynet.cinemarchive.core.model.TicketBarcodeFormat

/**
 * Decodes whatever barcode is printed on a captured ticket photo (GitHub #219) — tries every
 * symbology [MultiFormatReader] knows rather than assuming QR, since box-office printers use
 * whatever their vendor software defaults to (1D `CODE_128` is at least as common as a QR).
 * Reuses the `zxing-core` dependency [TicketQrCode] already pulls in for encoding — no new
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
    val result = runCatching { MultiFormatReader().decode(binaryBitmap) }.getOrNull() ?: return null
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
