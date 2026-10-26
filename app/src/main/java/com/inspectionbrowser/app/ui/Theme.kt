package com.inspectionbrowser.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun BrowserTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF7FB2FF), secondary = Color(0xFFFFC857))
        else -> lightColorScheme(primary = Color(0xFF1F5FBF), secondary = Color(0xFF8A6100))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Colours for syntax and status, readable in light and dark. */
object CodeColors {
    @Composable fun tag() = if (isSystemInDarkTheme()) Color(0xFF7FB2FF) else Color(0xFF1A4FA0)
    @Composable fun attrName() = if (isSystemInDarkTheme()) Color(0xFFE6B673) else Color(0xFF8A4B00)
    @Composable fun attrValue() = if (isSystemInDarkTheme()) Color(0xFF9CDB8A) else Color(0xFF1C7A1C)
    @Composable fun comment() = if (isSystemInDarkTheme()) Color(0xFF8A919C) else Color(0xFF6B717B)
    @Composable fun keyword() = if (isSystemInDarkTheme()) Color(0xFFD7A6FF) else Color(0xFF7A2DB8)
    @Composable fun number() = if (isSystemInDarkTheme()) Color(0xFFFF9E80) else Color(0xFFB0360F)
    @Composable fun error() = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else Color(0xFFC62828)
    @Composable fun ok() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    @Composable fun warn() = if (isSystemInDarkTheme()) Color(0xFFFFD54F) else Color(0xFF9A6700)
}
