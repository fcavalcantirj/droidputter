package com.droidputter.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * The link between a Droidputter app and whatever carries its bytes -- USB-CDC on the
 * phone, or a recorded fixture in tests/demo mode. [write] sends bytes to the ESP (KEY,
 * HELLO_ACK, PING); [incoming] emits raw chunks as they arrive, unframed -- feed them to
 * a [com.droidputter.core.protocol.Framer].
 */
interface DpTransport {
    fun write(bytes: ByteArray)

    val incoming: Flow<ByteArray>
}
