package com.roadlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadlink.domain.EmergencyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Colour for a delivery status label. Colour is meaning here, not decoration. */
fun statusColor(label: String): Color = when (label) {
    "DELIVERED" -> RlGreen
    "QUEUED" -> RlAmber
    "RETRYING" -> RlOrange
    "CONFIRMED" -> RlRed
    else -> RlBlue // any active *_RELAYING / *_UPLOAD state
}

@Composable
fun StatusChip(label: String, color: Color = statusColor(label)) {
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The honesty marker.
 *
 * Rendered wherever an event or a delivery is shown, and never suppressed. If
 * anything about an emergency was simulated, that is visible without reading
 * detail text - which is the whole point of giving it its own reserved colour.
 */
@Composable
fun FidelityBadge(simulated: Boolean, text: String? = null) {
    val color = if (simulated) RlViolet else RlGreen
    StatusChip(label = text ?: if (simulated) "SIMULATED" else "REAL", color = color)
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = RlSlate)
        Text(
            value,
            fontSize = 12.sp,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = RlSlate,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, fontSize = 13.sp, color = RlSlate)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

fun formatTime(epochMillis: Long): String = timeFormat.format(Date(epochMillis))

fun formatAge(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s ago"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s ago"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m ago"
}

fun formatLocation(event: EmergencyEvent): String =
    if (event.hasLocation) {
        "%.5f, %.5f%s".format(
            Locale.US, event.lat, event.lng,
            event.accuracyMetres?.let { " (±${it}m)" } ?: "",
        )
    } else {
        "no fix"
    }
