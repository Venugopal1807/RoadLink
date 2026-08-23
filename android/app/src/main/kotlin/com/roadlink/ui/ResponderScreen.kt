package com.roadlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadlink.net.ResponderEvent
import java.util.Locale

/**
 * What a responder sees.
 *
 * Sourced from the BACKEND, not from this device's database. That distinction
 * is the whole demo: the claim is that the emergency *arrived*, and only the
 * server can attest to it. A responder screen fed from local state would look
 * identical and prove nothing.
 */
@Composable
fun ResponderScreen(vm: RoadLinkViewModel) {
    val events by vm.responderEvents.collectAsState()
    val reachable by vm.backendReachable.collectAsState()
    val backendUrl by vm.backendUrl.collectAsState()

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            SectionLabel("Backend")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(backendUrl, fontSize = 11.sp, color = RlSlate)
                        when (reachable) {
                            true -> StatusChip("REACHABLE", RlGreen)
                            false -> StatusChip("UNREACHABLE", RlRed)
                            null -> StatusChip("CHECKING", RlSlate)
                        }
                    }
                }
            }
        }

        item { SectionLabel("Active emergencies (${events.size})") }

        if (events.isEmpty()) {
            item {
                EmptyState(
                    if (reachable == false) {
                        "Backend unreachable. Start it with:\n" +
                            "python -m uvicorn app.main:app --host 0.0.0.0 --port 8000"
                    } else {
                        "No active emergencies at the backend."
                    }
                )
            }
        }

        items(events, key = { it.eventId }) { event -> ResponderEventCard(event) }

        item { Text("", Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResponderEventCard(event: ResponderEvent) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            // ---- the line a responder reads first ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "EMERGENCY",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (event.simulated) RlViolet else RlRed,
                )
                StatusChip(event.state, if (event.state == "RECEIVED") RlRed else RlGreen)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Never suppressed. If the trigger was simulated, the responder
                // sees that before anything else.
                FidelityBadge(event.simulated)
                // And separately: how it actually got here.
                StatusChip(
                    label = event.firstDeliveryPath.uppercase(),
                    color = if (event.arrivedByRealTransport) RlBlue else RlViolet,
                )
                if (!event.signatureValid) StatusChip("UNVERIFIED SIGNATURE", RlOrange)
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            InfoRow("Event", event.eventId.take(8))
            InfoRow("Rider", event.riderId)
            InfoRow(
                "Location",
                if (event.hasLocation) {
                    "%.5f, %.5f%s".format(
                        Locale.US, event.lat, event.lng,
                        event.accuracyMetres?.let { " (±${it}m)" } ?: "",
                    )
                } else {
                    "no fix reported"
                },
                valueColor = if (event.hasLocation) MaterialTheme.colorScheme.onSurface else RlOrange,
            )
            InfoRow("Crash confidence", "${event.confidence}/100")
            InfoRow("Triggers", event.triggers.joinToString(", ").ifBlank { "none" })
            InfoRow("Device time", formatTime(event.createdAtDevice))
            // Server time is the one to trust: two phones in a relay handoff
            // can disagree by minutes, so device time is never used for order.
            InfoRow("Received (server)", event.receivedAt?.substringAfter('T')?.take(12) ?: "-")
            InfoRow("Delivery path", event.firstDeliveryPath)
            event.firstRelayId?.let { InfoRow("Relay", it) }

            if (event.simulated || !event.arrivedByRealTransport) {
                Text(
                    buildString {
                        if (event.simulated) append("Trigger was simulated. ")
                        if (!event.arrivedByRealTransport) {
                            append("Delivered by the development simulator, not a physical BLE relay.")
                        }
                    },
                    fontSize = 10.sp,
                    color = RlViolet,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
