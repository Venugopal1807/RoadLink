package com.roadlink.spike.ble

import java.util.UUID

/**
 * RoadLink BLE identifiers.
 *
 * These UUIDs were generated fresh (uuid4) for this project on 2026-08-21.
 * They are not derived from, and share nothing with, any prior work.
 */
object RoadLinkUuids {

    /** RoadLink Emergency Service. Advertised so a relay can filter for it. */
    val SERVICE: UUID = UUID.fromString("5a746561-2f72-41ee-b188-1b938007e52a")

    /** Full SOS packet. READ + NOTIFY, <= 512 bytes. Served by the peripheral. */
    val CHAR_SOS_PACKET: UUID = UUID.fromString("56d053bc-a578-44e4-8abe-4df8b607ec7a")

    /** Acknowledgement. WRITE (with response). Written by the central. */
    val CHAR_ACK: UUID = UUID.fromString("222881b9-b12a-46fb-a11e-b5fb6e9389dc")

    /** Spike telemetry: peripheral reports its own radio capabilities. READ. */
    val CHAR_SPIKE_META: UUID = UUID.fromString("9c425ae0-66bd-4dea-8ee5-9d87b1698cce")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Bluetooth SIG company identifier reserved for testing and development.
     *
     * A real deployment requires a SIG member company ID. This is a documented
     * prototype shortcut, not an oversight.
     */
    const val COMPANY_ID: Int = 0xFFFF
}
