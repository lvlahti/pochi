package io.github.lvlahti.pochi

import org.junit.Assert.*
import org.junit.Test

class VideoSettingsMuxerTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xE0.toByte(), 0x1f, 0xaa.toByte())
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 0x06)

    @Test fun customFrameRatesKeepFragmentsValid() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        val delta = byteArrayOf(0, 0, 0, 1, 0x41, 2)
        for (rate in listOf(15, 24, 30, 60)) {
            val muxer = H264Mp4Muxer(1280, 720, sps, pps, rate)
            muxer.offerFrame(idr, 0, true)
            val emitted = (1L..6L).flatMap { i ->
                muxer.offerFrame(delta, i * (1_000_000 / rate), false)
            }
            assertTrue("FPS $rate should produce MP4 fragments", emitted.isNotEmpty())
            assertTrue(emitted.first().startsWithKeyframe)
            assertTrue(emitted.first().bytes.toString(Charsets.ISO_8859_1).contains("moof"))
        }
    }
}