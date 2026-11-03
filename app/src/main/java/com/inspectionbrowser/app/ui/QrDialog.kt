package com.inspectionbrowser.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** [text] as a QR code: one pixel per module, scaled up sharply on screen. */
fun qrBitmap(text: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { if (matrix[it % w, it / w]) Color.BLACK else Color.WHITE }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** The page's address as a QR code, to open it on another device. */
@Composable
fun QrDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(url) { qrBitmap(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to open") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(), "QR code for $url",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(240.dp).background(androidx.compose.ui.graphics.Color.White),
                    )
                } else {
                    Text("This address is too long for a QR code.")
                }
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
            }) { Text("Share link") }
        },
    )
}
