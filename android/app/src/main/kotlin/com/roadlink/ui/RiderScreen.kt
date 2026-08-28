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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadlink.domain.CustodyStep
import com.roadlink.domain.CustodyTimeline
import com.roadlink.domain.DeliveryAttempt
import com.roadlink.domain.DeliveryState
import com.roadlink.domain.EmergencyEvent
import com.roadlink.domain.RiderStatus
import com.roadlink.domain.TransportKind

/**
 * The rider's device view.
 *
 * Ordered to answer one question before any other: **is my emergency safe?**
 * The status banner answers it, the trigger and the emergencies themselves
 * follow, and every development control is collapsed underneath.
 *
 * That order is not cosmetic. The controls used to come first, so the product
 * was the last thing on the screen and the first thing a reader saw was a
 * debugging console. The switches are still all here, still labelled, still
 * one tap away - they are just no longer the headline.
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
    val bleEnabled by vm.bleEnabled.collectAsState()
    val relayActive by vm.relayActive.collectAsState()
    val relayStatus by vm.relayStatus.collectAsState()
    val relayCollected by vm.relayCollected.collectAsState()
    val forced by vm.forcedTransport.collectAsState()

    val backendUrl by vm.backendUrl.collectAsState()
    val backendReachable by vm.backendReachable.collectAsState()

    // Collapsed by default. Everything under it is a development or demo
    // control, and none of it is part of the product being demonstrated.
    var showControls by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---- 1. the answer, before anything else ------------------------
        item {
            RiderStatusBanner(
                status = RiderStatus.ofDevice(events),
                held = events.count { it.state.isPending },
                delivered = events.count { it.state == DeliveryState.DELIVERED },
                backendReachable = backendReachable,
            )
        }

        // Configuration surfaces itself exactly when it is needed. An
        // unreachable backend is the one failure a first-time installer can
        // actually fix, and burying its only control under a collapsed section
        // would be the same mistake in a new place.
        if (backendReachable == false) {
            item {
                SectionLabel("Backend address  ·  action needed")
                BackendAddressCard(
                    current = backendUrl,
                    onApply = vm::setBackendUrl,
                    onReset = vm::resetBackendUrl,
                )
            }
        }

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


        item { SectionLabel("Emergencies held on this device (${events.size})") }

        if (events.isEmpty()) {
            item { EmptyState("No emergencies yet. Press CREATE TEST SOS.") }
        }

        items(events, key = { it.eventId }) { event ->
            LocalEventCard(
                event = event,
                attempts = attempts[event.eventId].orEmpty(),
                onRedeliver = { vm.redeliverDirect(event) },
            )
        }


        // ---- everything below is development and demo scaffolding ----
        item {
            SectionLabel("Demo and development controls")
            OutlinedButton(
                onClick = { showControls = !showControls },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (showControls) "Hide demo controls"
                    else "Show demo controls  ·  offline switch, transport pinning, BLE, relay mode",
                    fontSize = 11.sp,
                )
            }
            Text(
                "None of these are part of the product. They exist so a live demonstration " +
                    "is reproducible without waiting for a real outage.",
                fontSize = 10.sp, color = RlSlate,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (showControls) {
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
                SectionLabel("Delivery path (demo control)")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Pin delivery to one transport so a live demonstration is deterministic. " +
                                "AUTO is the real product behaviour.",
                            fontSize = 10.sp, color = RlSlate,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TransportChoice("AUTO", forced == null, RlSlate, Modifier.weight(1f)) {
                                vm.setForcedTransport(null)
                            }
                            TransportChoice(
                                "BLE", forced == TransportKind.BLE_RELAY, RlBlue, Modifier.weight(1f)
                            ) { vm.setForcedTransport(TransportKind.BLE_RELAY) }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TransportChoice(
                                "SIMULATED", forced == TransportKind.SIMULATED_RELAY, RlViolet, Modifier.weight(1f)
                            ) { vm.setForcedTransport(TransportKind.SIMULATED_RELAY) }
                            TransportChoice(
                                "NETWORK", forced == TransportKind.DIRECT_NETWORK, RlGreen, Modifier.weight(1f)
                            ) { vm.setForcedTransport(TransportKind.DIRECT_NETWORK) }
                        }
                    }
                }
            }

            item {
                SectionLabel("Physical BLE  ·  S0 capability gate")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("BLE transport", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            StatusChip(
                                if (bleEnabled) "ARMED" else "NOT VALIDATED",
                                if (bleEnabled) RlBlue else RlOrange,
                            )
                        }
                        Text(
                            if (bleEnabled) {
                                "Armed. Deliveries over this path are PHYSICAL BLE and count as evidence."
                            } else {
                                "Disarmed until S0-S5 pass on two physical phones. No BLE claim in " +
                                    "this build is backed by hardware evidence yet."
                            },
                            fontSize = 10.sp,
                            color = if (bleEnabled) RlBlue else RlSlate,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )

                        bleCapability?.let { cap ->
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            InfoRow("Device", "${cap.manufacturer} ${cap.deviceModel}")
                            InfoRow("Android", "${cap.androidRelease} (API ${cap.apiLevel})")
                            InfoRow("BLE feature", cap.hasBleFeature.toString())
                            InfoRow("Adapter enabled", cap.adapterEnabled.toString())
                            InfoRow(
                                cap.advertiseVerdict,
                                if (cap.canAdvertise) "yes" else "no",
                                valueColor = if (cap.canAdvertise) RlGreen else RlRed,
                            )
                            InfoRow("Scanner", if (cap.scannerAvailable) "available" else "absent")
                            InfoRow("Extended adv", cap.extendedAdvertisingSupported.toString())
                            InfoRow("LE 2M PHY", cap.le2MPhySupported.toString())
                            InfoRow("Max adv data", "${cap.maxAdvertisingDataLength} B")
                            cap.peripheralBlocker?.let {
                                Text(it, fontSize = 11.sp, color = RlRed, modifier = Modifier.padding(top = 4.dp))
                            }
                        }

                        OutlinedButton(
                            onClick = { vm.probeBle() },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("Run S0 probe on this device", fontSize = 12.sp) }

                        SwitchRow(
                            label = "Arm physical BLE transport",
                            detail = "turn on only after S0 reports CAN ADVERTISE",
                            checked = bleEnabled,
                            onChange = vm::setBleEnabled,
                        )
                    }
                }
            }

            item {
                SectionLabel("Relay mode  ·  act as Phone B")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Carry emergencies for riders nearby", fontSize = 12.sp)
                            StatusChip(relayStatus.uppercase(), if (relayActive) RlBlue else RlSlate)
                        }
                        Text(
                            "A collected emergency is stored here and then uploaded by the same " +
                                "delivery loop as this phone's own. Nothing is acknowledged to the " +
                                "rider until it is safely on disk.",
                            fontSize = 10.sp, color = RlSlate,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        InfoRow("Collected this session", relayCollected.toString())
                        SwitchRow(
                            label = "Relay mode",
                            detail = "scan for riders and take custody of their emergencies",
                            checked = relayActive,
                            onChange = vm::setRelayMode,
                        )
                        OutlinedButton(
                            onClick = { vm.forgetRelayCollected() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Forget collected (repeatability run)", fontSize = 11.sp) }
                    }
                }
            }


            // Shown here only when it is not already surfaced above.
            if (backendReachable != false) {
                item {
                    SectionLabel("Backend address")
                    BackendAddressCard(
                        current = backendUrl,
                        onApply = vm::setBackendUrl,
                        onReset = vm::resetBackendUrl,
                    )
                }
            }
        }
        item { Text("", Modifier.height(24.dp)) }
    }
}

/**
 * Where this install sends emergencies.
 *
 * Present because the build-time default points at whatever machine produced
 * the APK. Anyone else installing it has to be able to redirect it, or the app
 * can never reach a backend. Editing this changes nothing about persistence:
 * queued emergencies stay queued and are delivered to the new address.
 */
@Composable
private fun BackendAddressCard(
    current: String,
    onApply: (String) -> Unit,
    onReset: () -> Unit,
) {
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    val dirty = draft.trim().trimEnd('/') != current

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Emergencies are sent here. Set it to the machine running the " +
                    "RoadLink backend, then press Apply.",
                fontSize = 10.sp, color = RlSlate,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("Backend URL", fontSize = 11.sp) },
                placeholder = { Text("http://192.168.1.10:8000", fontSize = 11.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onApply(draft) },
                    enabled = dirty,
                    modifier = Modifier.weight(1f),
                ) { Text("Apply", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { onReset() },
                    modifier = Modifier.weight(1f),
                ) { Text("Reset to default", fontSize = 12.sp) }
            }
            Text(
                "In use: $current",
                fontSize = 10.sp,
                color = if (dirty) RlAmber else RlSlate,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (dirty) {
                Text(
                    "Unapplied change. Press Apply to use it.",
                    fontSize = 10.sp, color = RlAmber,
                )
            }
        }
    }
}

/**
 * The first thing on the screen, and the only thing that has to be understood.
 *
 * It answers "is my emergency safe?" before the reader has to interpret a list.
 * The status wording comes from [RiderStatus], which is unit tested precisely
 * because these are the sentences that must not be wrong - most of all
 * FAILED BUT RETAINED, where the attempt failed and the emergency did not.
 */
@Composable
private fun RiderStatusBanner(
    status: RiderStatus,
    held: Int,
    delivered: Int,
    backendReachable: Boolean?,
) {
    val color = riderStatusColor(status)
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(status.label, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = color)
                StatusChip(
                    when (backendReachable) {
                        true -> "BACKEND REACHABLE"
                        false -> "BACKEND UNREACHABLE"
                        null -> "CHECKING BACKEND"
                    },
                    when (backendReachable) {
                        true -> RlGreen
                        false -> RlOrange
                        null -> RlSlate
                    },
                )
            }

            Text(
                status.meaning,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            InfoRow("Held on this phone", held.toString())
            InfoRow("Confirmed by the backend", delivered.toString())

            // The product claim, stated on the product rather than only in the
            // README. An unreachable backend is the moment it is worth saying.
            if (backendReachable == false) {
                Text(
                    "The backend cannot be reached. Nothing is lost: emergencies stay on this " +
                        "phone and are retried automatically until a delivery path exists.",
                    fontSize = 11.sp,
                    color = RlOrange,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * What actually happened to one emergency, in order.
 *
 * Built by [CustodyTimeline] from stored rows only - the event's own fields and
 * its append-only attempt trail. If a line appears here, a row exists that says
 * so; nothing is inferred to make the story read better.
 */
@Composable
private fun CustodyTimelineView(steps: List<CustodyStep>) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SectionLabel("Custody trail")
        steps.forEach { step ->
            val color = when (step.kind) {
                CustodyStep.Kind.DELIVERED -> RlGreen
                CustodyStep.Kind.RELAYED -> RlAmber
                CustodyStep.Kind.FAILED -> RlOrange
                CustodyStep.Kind.INTERRUPTED -> RlOrange
                CustodyStep.Kind.UNAVAILABLE -> RlSlate
                CustodyStep.Kind.PERSISTED -> RlRed
                CustodyStep.Kind.WAITING -> RlAmber
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    formatTime(step.at),
                    fontSize = 10.sp,
                    color = RlSlate,
                )
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(step.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
                        // A simulated hop is marked on the step itself, so a
                        // screenshot of this trail cannot be mistaken for
                        // evidence of a real radio.
                        if (step.isSimulated) FidelityBadge(simulated = true)
                    }
                    step.detail?.let {
                        Text(it, fontSize = 10.sp, color = RlSlate)
                    }
                }
            }
        }
    }
}

/** A single option in the demo transport selector. */
@Composable
private fun TransportChoice(
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = color),
        ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, fontSize = 11.sp, color = RlSlate)
        }
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
    attempts: List<DeliveryAttempt>,
    onRedeliver: () -> Unit,
) {
    val status = RiderStatus.of(event)
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
                    // Someone else's emergency that this phone is carrying.
                    if (event.collectedAsRelay) StatusChip("CARRYING", RlBlue)
                    FidelityBadge(event.simulated, if (event.simulated) "SIMULATED TRIGGER" else "REAL TRIGGER")
                    StatusChip(status.label, riderStatusColor(status))
                }
            }
            // The status in one sentence, on the card, so the chip is never the
            // only thing carrying the meaning.
            Text(
                status.meaning,
                fontSize = 10.sp,
                color = riderStatusColor(status),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            if (event.collectedAsRelay) {
                Text(
                    "Collected over BLE from rider ${event.riderId}. This phone is acting as a relay.",
                    fontSize = 10.sp, color = RlBlue,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            InfoRow("Created", formatTime(event.createdAt))
            InfoRow("Location", formatLocation(event))
            InfoRow("Confidence", "${event.confidence}/100")
            InfoRow("Triggers", event.triggers.joinToString(", ").ifBlank { "none" })
            InfoRow("Delivery attempts", "${event.attemptCount} (recorded: ${attempts.size})")

            event.relayedTo?.let {
                InfoRow("Handed to relay", it, valueColor = RlAmber)
            }
            if (event.state == DeliveryState.RELAYED) {
                Text(
                    "A relay has custody. The backend has NOT confirmed it yet, so this phone " +
                        "keeps trying to deliver independently.",
                    fontSize = 10.sp, color = RlAmber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            event.deliveredVia?.let {
                InfoRow("Delivered via", it.label, valueColor = if (it.isSimulated) RlViolet else RlGreen)
            }
            event.lastError?.let {
                InfoRow("Last error", it, valueColor = RlOrange)
            }

            CustodyTimelineView(CustodyTimeline.of(event, attempts))

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
