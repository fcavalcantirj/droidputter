package com.droidputter.core.catalog

import java.security.MessageDigest

/** Lower-case hex SHA-256 of [data]: the identity of a downloaded catalog part / firmware build. */
fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

/**
 * The part whose hash identifies the build (what a community verdict is tied to): `firmware.bin` when the
 * entry is split into bootloader/partitions/boot_app0/firmware, else the highest-offset part -- which for
 * a merged single-image entry (LauncherHub) is its only part. Null for an entry with no parts.
 */
fun CatalogEntry.firmwarePart(): CatalogPart? =
    parts.firstOrNull { it.file == "firmware.bin" }
        ?: parts.maxByOrNull { it.offset.removePrefix("0x").toLong(16) }
