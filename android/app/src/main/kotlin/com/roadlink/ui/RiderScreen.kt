package com.roadlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EmergencyEvent

/**
 * The rider's device view: what this phone is holding, and the controls that
 * make the delivery scenarios reproducible on demand.
 */
@Composable
fun RiderScreen(vm: RoadLinkViewModel) {
    val events by vm.events.collectAsState()
    val attempts by vm.attempts.collectAsState()
    val busy by vm.busy.collectAsState()
    val relayInRange by vm.relayInRange.collectAsState()
    val relayHasNetwork by vm.relayHasNetwork.collectAsState()
    val forcedOffline by vm.forcedOffline.collectAsState()
    val failures by vm.scriptedFailures.collectAsState()
    val bleCapability by vm.bleCapability.collectAsState()

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            SectionLabel("Trigger")
            Button(
                onClick = { vm.createTestSos() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RlRed),
            ) {
                Text("CREATE TEST SOS", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FidelityBadge(simulated = true, text = "SIMULATED TRIGGER")
                Text(
                    "Only the trigger is simulated. Signing, persistence, state machine, " +
                        "transport and backend are the real pipeline.",
                    fontSize = 10.sp,
                    color = RlSlate,
                )
            }
        }

        item {
            SectionLabel("Development controls")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    SwitchRow(
                        label = "Simulated relay in range",
                        detail = "off = no nearby phone (scenario B/E)",
                        checked = relayInRange,
                        onChange = vm::setRelayInRange,
                    )
                    SwitchRow(
                        label = "Simulated relay has connectivity",
                        detail = "off = relay holds the SOS but cannot forward it",
                        checked = relayHasNetwork,
                        onChange = vm::setRelayHasNetwork,
                    )
                    SwitchRow(
                        label = "Force rider offline",
                        detail = "blocks direct upload without touching aeroplane mode",
                        checked = forcedOffline,
                        onChange = vm::setForcedOffline,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Scripted relay failures", fontSize = 13.sp)
                            Text(
                                "relay fails this many times before succeeding (scenario C)",
                                fontSize = 10.sp, color = RlSlate,
                            )
                        }
                        OutlinedButton(onClick = { vm.setScriptedFailures(failures - 1) }) { Text("−") }
                        Text(
                            "$failures",
                            Modifier.padding(horizontal = 10.dp),
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(onClick = { vm.setScriptedFailures(failures + 1) }) { Text("+") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { vm.deliverNow() },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Deliver now", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { vm.resetSimulator() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Reset script", fontSize = 12.sp) }
                    }
                }
            }
        }

        item {
            SectionLabel("BLE relay status")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("BLE transport", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        StatusChip("NOT VALIDATED", RlOrange)
                    }
                    Text(
                        "Disabled until the S0-S5 ladder passes on two physical phones. " +
                            "No BLE claim in this build is backed by hardware evidence.",
                        fontSize = 10.sp, color = RlSlate,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    bleCapability?.let { cap ->
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        InfoRow("Device", cap.deviceModel)
                        InfoRow("API level", cap.apiLevel.toString())
                        InfoRow("BLE feature", cap.hasBleFeature.toString())
                        InfoRow("Adapter enabled", cap.adapterEnabled.toString())
                        InfoRow(
                            "CAN ADVERTISE",
                            cap.canAdvertise.toString(),
                            valueColor = if (cap.canAdvertise) RlGreen else RlRed,
                        )
                        cap.blocker?.let {
                            Text(it, fontSize = 11.sp, color = RlRed, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.probeBle() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Probe this device's radio", fontSize = 12.sp) }
                }
            }
        }

        item { SectionLabel("Emergencies held on this device (${events.size})") }

        if (events.isEmpty()) {
            item { EmptyState("No emergencies yet. Press CREATE TEST SOS.") }
        }

        items(events, key = { it.eventId }) { event ->
            LocalEventCard(
                event = event,
                attemptCount = attempts[event.eventId]?.size ?: 0,
                onRedeliver = { vm.redeliverDirect(event) },
            )
        }

        item { Text("", Modifier.height(24.dp)) }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            Text(detail, fontSize = 10.sp, color = RlSlate)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LocalEventCard(
    event: EmergencyEvent,
    attemptCount: Int,
    onRedeliver: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    event.eventId.take(8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FidelityBadge(event.simulated)
                    StatusChip(event.statusLabel)
                }
            }
            InfoRow("Created", formatTime(event.createdAt))
            InfoRow("Location", formatLocation(event))
            InfoRow("Confidence", "${event.confidence}/100")
            InfoRow("Triggers", event.triggers.joinToString(", ").ifBlank { "none" })
            InfoRow("Delivery attempts", "${event.attemptCount} (logged: $attemptCount)")

            event.deliveredVia?.let {
                InfoRow("Delivered via", it.label, valueColor = if (it.isSimulated) RlViolet else RlGreen)
            }
            event.lastError?.let {
                InfoRow("Last error", it, valueColor = RlOrange)
            }

            if (event.state == DeliveryState.DELIVERED) {
                OutlinedButton(
                    onClick = onRedeliver,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Submit again over direct network (idempotency check)", fontSize = 11.sp)
                }
            }
        }
    }
}
