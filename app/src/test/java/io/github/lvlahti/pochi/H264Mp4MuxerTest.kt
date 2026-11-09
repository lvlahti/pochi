package io.github.lvlahti.pochi

import org.junit.Assert.*
import org.junit.Test

class H264Mp4MuxerTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xE0.toByte(), 0x1F, 0xAA.toByte(), 0xBB.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())

    @Test fun nalSplitterSupportsThreeAndFourByteStartCodes() {
        val input = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0, 0, 1, 0x41, 0x22)
        val output = AvcNal.split(input)
        assertEquals(2, output.size)
        assertArrayEquals(byteArrayOf(0x65, 0x11), output[0])
        assertArrayEquals(byteArrayOf(0x41, 0x22), output[1])
    }

    @Test fun initSegmentAndFragmentsContainMp4Boxes() {
        val muxer = H264Mp4Muxer(720, 1280, sps, pps)
        assertEquals("avc1.42E01F", muxer.codecString)
        val init = muxer.initSegment()
        assertEquals("ftyp", nameAt(init, 4))
        assertTrue(init.toString(Charsets.ISO_8859_1).contains("moov"))
        assertTrue(init.toString(Charsets.ISO_8859_1).contains("avcC"))
        assertTrue(init.toString(Charsets.ISO_8859_1).contains("mvex"))
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x33, 0x44)
        val inter = byteArrayOf(0, 0, 1, 0x41, 0x22, 0x11)
        assertTrue(muxer.offerFrame(inter, 0, false).isEmpty()) // no keyframe yet
        assertTrue(muxer.offerFrame(idr, 41_666, true).isEmpty())
        var emitted = emptyList<H264Mp4Muxer.Fragment>()
        repeat(5) { emitted = muxer.offerFrame(inter, 83_333L + 41_666L * it, false) }
        assertEquals(1, emitted.size)
        val bytes = emitted.first().bytes
        assertEquals("moof", nameAt(bytes, 4))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("mdat"))
        assertTrue(emitted.first().startsWithKeyframe)
    }

    @Test fun avccInputIsRecognized() {
        assertArrayEquals(byteArrayOf(0x65, 0x20),
            AvcNal.split(byteArrayOf(0, 0, 0, 2, 0x65, 0x20)).single())
    }

    private fun nameAt(array: ByteArray, start: Int): String =
        String(array.copyOfRange(start, start + 4), Charsets.US_ASCII)
}