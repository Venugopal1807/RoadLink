package com.roadlink.spike

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.roadlink.spike.ble.Beacon
import com.roadlink.spike.ble.CentralRole
import com.roadlink.spike.ble.PeripheralRole
import com.roadlink.spike.ble.RadioCapabilities
import java.util.UUID

/**
 * RoadLink BLE spike - S0 through S5.
 *
 * The same APK installs on both phones. The role is chosen at runtime:
 *   PHONE A -> peripheral: advertises + serves GATT + receives the ACK
 *   PHONE B -> central:    scans + connects + reads + ACKs
 *
 * Deliberately built with framework views only - no AndroidX, no Compose, no
 * coroutines. This module has zero external dependencies so a radio problem
 * can never be confused with a dependency-resolution problem.
 */
class SpikeActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView

    private lateinit var capabilities: RadioCapabilities
    private var peripheral: PeripheralRole? = null
    private var central: CentralRole? = null

    private var currentEventId: String = UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capabilities = RadioCapabilities(this)
        setContentView(buildUi())

        SpikeLog.listener = { appendLine(it) }
        SpikeLog.log("RoadLink spike ready on ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")

        if (!SelfTest.run()) {
            toast("Codec self-tests FAILED — see log")
        }

        requestBlePermissions()
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "RoadLink BLE Spike  ·  S0–S5"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply {
            text = "role: none"
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(statusView)

        // --- S0 row
        root.addView(sectionLabel("S0 · capability probe"))
        root.addView(row(
            button("Probe radio") { runProbe() },
            button("T2 scanRsp") { runCeiling(inScanResponse = true) },
            button("T2 advData") { runCeiling(inScanResponse = false) },
        ))

        // --- role row
        root.addView(sectionLabel("S1–S5 · pick ONE role per phone"))
        root.addView(row(
            button("PHONE A (peripheral)") { startPeripheral() },
            button("PHONE B (central)") { startCentral() },
        ))
        root.addView(row(
            button("Notify (A)") { peripheral?.notifySubscribers("PING ${System.currentTimeMillis()}") },
            button("Rescan (B)") { central?.forgetSeen(); central?.startScan() },
            button("Stop") { stopAll() },
        ))

        // --- log row
        root.addView(sectionLabel("log  (logcat tag: RLSPIKE)"))
        root.addView(row(
            button("Clear") { SpikeLog.clear(); logView.text = "" },
            button("Copy") { copyLog() },
            button("New event") {
                currentEventId = UUID.randomUUID().toString()
                SpikeLog.log("new test event_id = $currentEventId")
            },
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#0E1116"))
            setTextColor(Color.parseColor("#D6E2F0"))
            setPadding(12, 12, 12, 12)
        }
        scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        return root
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 16, 0, 4)
    }

    private fun row(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        children.forEach {
            addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun appendLine(line: String) {
        logView.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RLSPIKE", SpikeLog.snapshot()))
        toast("Log copied")
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- S0 / T2

    private fun runProbe() {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        SpikeLog.mark("S0 capability probe")
        val report = capabilities.probe()
        if (!report.canBePeripheral) {
            SpikeLog.logError(
                "This device CANNOT take the Phone A role. " +
                    if (!report.adapterEnabled) "Bluetooth is off."
                    else "No LE advertiser: the chipset does not support the peripheral role."
            )
        }
    }

    private fun runCeiling(inScanResponse: Boolean) {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        capabilities.probePayloadCeiling(inScanResponse) { ceiling ->
            SpikeLog.log(
                "T2 RESULT (${if (inScanResponse) "scanResponse" else "advData"}): " +
                    "max manufacturer-data payload = $ceiling bytes " +
                    "(RoadLink beacon needs ${Beacon.SIZE})"
            )
        }
    }

    // ------------------------------------------------------------ roles

    private fun startPeripheral() {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        stopAll()
        SpikeLog.mark("PHONE A - peripheral")

        val report = capabilities.probe()
        if (!report.canBePeripheral) {
            toast("This phone cannot advertise — it cannot be Phone A")
            return
        }

        val beacon = Beacon(
            simulated = true,          // S0–S5 is always a simulated event
            hasLocation = false,       // no GPS until S6
            eventRef = Beacon.eventRefOf(currentEventId),
            confidence = 82,
            ageSeconds = 0,
        )
        SpikeLog.log("event_id = $currentEventId")

        peripheral = PeripheralRole(this).apply {
            events = object : PeripheralRole.Events {
                override fun onAdvertisingStarted(beacon: Beacon) {
                    setStatus("role: PHONE A · advertising · ref=${beacon.refHex()}")
                }
                override fun onAdvertisingFailed(reason: String) {
                    setStatus("role: PHONE A · FAILED")
                    toast("Advertising failed: $reason")
                }
                override fun onCentralConnected(address: String) {
                    setStatus("role: PHONE A · central connected $address")
                }
                override fun onCentralDisconnected(address: String) {
                    setStatus("role: PHONE A · advertising")
                }
                override fun onPacketRead(address: String) {
                    setStatus("role: PHONE A · packet read by $address")
                }
                override fun onAckReceived(address: String, ack: String) {
                    setStatus("role: PHONE A · ACKED by $address")
                    SpikeLog.log("S4 COMPLETE: event would now move QUEUED_OFFLINE -> RELAYING")
                    toast("ACK received")
                }
                override fun onNotificationsEnabled(address: String, enabled: Boolean) {}
            }
            start(beacon)
        }
    }

    private fun startCentral() {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        stopAll()
        SpikeLog.mark("PHONE B - central")

        central = CentralRole(this).apply {
            events = object : CentralRole.Events {
                override fun onBeaconDiscovered(address: String, rssi: Int, beacon: Beacon) {
                    setStatus("role: PHONE B · found $address rssi=$rssi")
                }
                override fun onConnected(address: String) {
                    setStatus("role: PHONE B · connected $address")
                }
                override fun onServicesDiscovered(address: String) {
                    setStatus("role: PHONE B · services discovered")
                }
                override fun onPayloadRead(address: String, payload: ByteArray) {
                    setStatus("role: PHONE B · read ${payload.size} B")
                }
                override fun onNotification(address: String, value: ByteArray) {}
                override fun onAckWritten(address: String) {
                    setStatus("role: PHONE B · ACK sent ✓")
                    SpikeLog.log("S1–S4 COMPLETE for this peer")
                    toast("ACK written")
                }
                override fun onDisconnected(address: String, status: Int, willRetry: Boolean) {
                    setStatus("role: PHONE B · disconnected" + if (willRetry) " (retrying)" else "")
                }
                override fun onFailure(stage: String, detail: String) {
                    setStatus("role: PHONE B · $stage FAILED")
                    toast("$stage failed: $detail")
                }
            }
            startScan()
        }
        setStatus("role: PHONE B · scanning")
    }

    private fun stopAll() {
        peripheral?.stop(); peripheral = null
        central?.stop(); central = null
        setStatus("role: none")
    }

    override fun onDestroy() {
        stopAll()
        SpikeLog.listener = null
        super.onDestroy()
    }

    // --------------------------------------------------------- permissions

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // On API <= 30, LE scanning is gated behind fine location.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBlePermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBlePermissions() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            SpikeLog.log("permissions: all granted (${requiredPermissions().joinToString { it.substringAfterLast('.') }})")
            return
        }
        SpikeLog.log("permissions: requesting ${missing.joinToString { it.substringAfterLast('.') }}")
        requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        // T3 evidence: record exactly what was granted and what was refused.
        permissions.forEachIndexed { i, permission ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            SpikeLog.log("permission ${permission.substringAfterLast('.')} -> ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    private companion object {
        const val REQ_PERMISSIONS = 1001
    }
}
