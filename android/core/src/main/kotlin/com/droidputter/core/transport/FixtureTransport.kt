package com.droidputter.core.transport

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a recorded fixture (`tools/dp_receiver.py --record NAME` -> NAME.bin + NAME.jsonl)
 * as a [DpTransport]. NAME.jsonl carries one JSON object per chunk: `dir: "in"` entries have
 * `t_ms` and `n` (byte count) for a chunk of NAME.bin's raw incoming stream, in order; `dir:
 * "out"` entries are frames the recorder itself wrote back (KEY/HELLO_ACK, carried as `hex`)
 * and aren't replayed here. [speedFactor] > 1 replays faster than real time -- tests use a
 * large factor so a multi-second hardware capture completes in milliseconds. [write] just
 * records what the app under test sent, there being no real device on the other end to see it.
 */
class FixtureTransport(basePath: String, private val speedFactor: Double = 1.0) : DpTransport {
    private val binFile = File("$basePath.bin")
    private val jsonlFile = File("$basePath.jsonl")

    private val _writes = mutableListOf<ByteArray>()
    val writes: List<ByteArray> get() = _writes

    override fun write(bytes: ByteArray) {
        _writes += bytes
    }

    override val incoming: Flow<ByteArray> = flow {
        val bytes = binFile.readBytes()
        var offset = 0
        var lastTMs = 0.0
        for (line in jsonlFile.readLines()) {
            val dir = DIR_RE.find(line)?.groupValues?.get(1) ?: continue
            if (dir != "in") continue
            val tMs = T_MS_RE.find(line)?.groupValues?.get(1)?.toDouble() ?: continue
            val n = N_RE.find(line)?.groupValues?.get(1)?.toInt() ?: continue

            val waitMs = ((tMs - lastTMs) / speedFactor).toLong()
            if (waitMs > 0) delay(waitMs)
            lastTMs = tMs

            emit(bytes.copyOfRange(offset, offset + n))
            offset += n
        }
    }

    private companion object {
        val DIR_RE = Regex(""""dir"\s*:\s*"(\w+)"""")
        val T_MS_RE = Regex(""""t_ms"\s*:\s*([0-9.]+)""")
        val N_RE = Regex(""""n"\s*:\s*([0-9]+)""")
    }
}
