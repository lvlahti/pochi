package io.github.lvlahti.pochi

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** A minimal single-track fragmented MP4 muxer for Annex-B AVC from MediaCodec.
 *
 * No B frames: PTS == DTS, which is the normal Surface H.264 low-latency encoder path.
 * The output can be appended to a browser MediaSource SourceBuffer.
 */
class H264Mp4Muxer(
    private val width: Int,
    private val height: Int,
    private val sps: ByteArray,
    private val pps: ByteArray,
    private val frameRate: Int = 24
) {
    data class Fragment(val bytes: ByteArray, val startsWithKeyframe: Boolean)
    private data class Sample(val payload: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    val codecString: String = "avc1.%02X%02X%02X".format(
        sps.getOrElse(1) { 0 }.toInt() and 255,
        sps.getOrElse(2) { 0 }.toInt() and 255,
        sps.getOrElse(3) { 0 }.toInt() and 255
    )

    private val waiting = ArrayList<Sample>(6)
    private var decodeTime = 0L
    private var sequence = 1
    private var receivedKeyframe = false

    fun initSegment(): ByteArray {
        val ftyp = Box.box("ftyp", Box.build {
            text("isom"); i32(0x200); text("isomiso6avc1mp41")
        })
        val avcC = Box.box("avcC", Box.build {
            u8(1)
            u8(sps[1].toInt() and 255)
            u8(sps[2].toInt() and 255)
            u8(sps[3].toInt() and 255)
            u8(0xFF) // NAL length size = 4
            u8(0xE1) // one SPS
            u16(sps.size); raw(sps)
            u8(1); u16(pps.size); raw(pps)
        })
        val avc1 = Box.box("avc1", Box.build {
            raw(ByteArray(6)); u16(1) // reserved + data reference index
            raw(ByteArray(16))
            u16(width); u16(height)
            i32(0x00480000); i32(0x00480000)
            i32(0); u16(1)
            raw(ByteArray(32)) // compressor name
            u16(0x18); u16(0xFFFF)
            raw(avcC)
        })
        val stbl = Box.box("stbl",
            Box.full("stsd", 0, 0, Box.build { i32(1); raw(avc1) }),
            Box.full("stts", 0, 0, Box.build { i32(0) }),
            Box.full("stsc", 0, 0, Box.build { i32(0) }),
            Box.full("stsz", 0, 0, Box.build { i32(0); i32(0) }),
            Box.full("stco", 0, 0, Box.build { i32(0) })
        )
        val minf = Box.box("minf",
            Box.full("vmhd", 0, 1, ByteArray(8)),
            Box.box("dinf", Box.full("dref", 0, 0,
                Box.build { i32(1); raw(Box.full("url ", 0, 1, byteArrayOf())) })),
            stbl
        )
        val mdia = Box.box("mdia",
            Box.full("mdhd", 0, 0, Box.build {
                i32(0); i32(0); i32(TIMESCALE); i32(0); u16(0x55C4); u16(0)
            }),
            Box.full("hdlr", 0, 0, Box.build {
                i32(0); text("vide"); raw(ByteArray(12)); text("VideoHandler"); u8(0)
            }),
            minf
        )
        val trak = Box.box("trak",
            Box.full("tkhd", 0, 7, Box.build {
                i32(0); i32(0); i32(1); i32(0); i32(0)
                raw(ByteArray(8)); u16(0); u16(0); u16(0); u16(0)
                identityMatrix()
                i32(width shl 16); i32(height shl 16)
            }),
            mdia
        )
        val mvhd = Box.full("mvhd", 0, 0, Box.build {
            i32(0); i32(0); i32(TIMESCALE); i32(0)
            i32(0x00010000); u16(0x0100); u16(0)
            raw(ByteArray(8)); identityMatrix(); raw(ByteArray(24)); i32(2)
        })
        val trex = Box.full("trex", 0, 0, Box.build {
            i32(1); i32(1); i32(0); i32(0); i32(0)
        })
        return Box.concat(ftyp, Box.box("moov", mvhd, trak, Box.box("mvex", trex)))
    }

    fun offerFrame(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean): List<Fragment> {
        val nals = AvcNal.split(data).filter {
            val type = it[0].toInt() and 31
            type != 7 && type != 8 && type != 9 // exclude SPS, PPS and AUD
        }
        if (nals.isEmpty()) return emptyList()
        val keyframe = isKeyFrame || nals.any { (it[0].toInt() and 31) == 5 }
        if (!receivedKeyframe && !keyframe) return emptyList()
        receivedKeyframe = true
        val fragments = mutableListOf<Fragment>()
        // Always start a new fragment at the IDR boundary so a new viewer can join.
        if (keyframe && waiting.isNotEmpty()) fragments.add(flush())
        val sampleBytes = Box.build {
            nals.forEach { nal -> i32(nal.size); raw(nal) }
        }
        waiting.add(Sample(sampleBytes, ptsUs, keyframe))
        if (waiting.size >= 6) fragments.add(flush())
        return fragments
    }

    fun finish(): Fragment? = if (waiting.isEmpty()) null else flush()

    private fun flush(): Fragment {
        val samples = waiting.toList()
        waiting.clear()
        val nominalDuration = TIMESCALE / frameRate
        val durations = samples.mapIndexed { index, sample ->
            if (index + 1 >= samples.size) nominalDuration else {
                (((samples[index + 1].ptsUs - sample.ptsUs) * TIMESCALE) / 1_000_000L)
                    .coerceIn(1_200L, 9_000L).toInt()
            }
        }
        val payload = Box.build { samples.forEach { raw(it.payload) } }
        val mfhd = Box.full("mfhd", 0, 0, Box.build { i32(sequence++) })
        val tfhd = Box.full("tfhd", 0, 0x020000, Box.build { i32(1) })
        val tfdt = Box.full("tfdt", 1, 0, Box.build { i64(decodeTime) })
        fun trun(offset: Int) = Box.full("trun", 0, 0x000701, Box.build {
            i32(samples.size); i32(offset)
            samples.forEachIndexed { index, sample ->
                i32(durations[index]); i32(sample.payload.size)
                i32(if (sample.keyframe) 0x02000000 else 0x01010000)
            }
        })
        val provisional = Box.box("moof", mfhd, Box.box("traf", tfhd, tfdt, trun(0)))
        val moof = Box.box("moof", mfhd,
            Box.box("traf", tfhd, tfdt, trun(provisional.size + 8)))
        decodeTime += durations.sum().toLong()
        return Fragment(Box.concat(moof, Box.box("mdat", payload)), samples.first().keyframe)
    }

    companion object {
        const val TIMESCALE = 90_000
    }
}

/** Parse H.264 NAL units with 3- or 4-byte Annex B start codes, with AVCC fallback. */
object AvcNal {
    fun split(bytes: ByteArray): List<ByteArray> {
        fun codeLength(index: Int): Int {
            if (index + 2 >= bytes.size || bytes[index] != 0.toByte() || bytes[index + 1] != 0.toByte()) return 0
            if (bytes[index + 2] == 1.toByte()) return 3
            if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) return 4
            return 0
        }
        val output = ArrayList<ByteArray>()
        var index = 0
        while (index < bytes.size && codeLength(index) == 0) index++
        if (index < bytes.size) {
            while (index < bytes.size) {
                val length = codeLength(index)
                if (length == 0) { index++; continue }
                val start = index + length
                index = start
                while (index < bytes.size && codeLength(index) == 0) index++
                var end = index
                while (end > start && bytes[end - 1] == 0.toByte()) end--
                if (end > start) output.add(bytes.copyOfRange(start, end))
            }
            return output
        }
        // Some encoders yield AVCC-length-prefixed buffers instead of Annex B.
        index = 0
        while (index + 4 <= bytes.size) {
            val size = ((bytes[index].toInt() and 255) shl 24) or
                ((bytes[index + 1].toInt() and 255) shl 16) or
                ((bytes[index + 2].toInt() and 255) shl 8) or
                (bytes[index + 3].toInt() and 255)
            if (size <= 0 || index + 4 + size > bytes.size) break
            output.add(bytes.copyOfRange(index + 4, index + 4 + size))
            index += 4 + size
        }
        return if (index == bytes.size && output.isNotEmpty()) output else if (bytes.isNotEmpty()) listOf(bytes) else emptyList()
    }
}

private object Box {
    class Writer {
        private val stream = ByteArrayOutputStream()
        private val out = DataOutputStream(stream)
        fun u8(value: Int) = out.writeByte(value)
        fun u16(value: Int) = out.writeShort(value)
        fun i32(value: Int) = out.writeInt(value)
        fun i64(value: Long) = out.writeLong(value)
        fun text(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun raw(bytes: ByteArray) = out.write(bytes)
        fun identityMatrix() {
            i32(0x10000); i32(0); i32(0)
            i32(0); i32(0x10000); i32(0)
            i32(0); i32(0); i32(0x40000000)
        }
        fun toBytes() = stream.toByteArray()
    }

    fun build(block: Writer.() -> Unit): ByteArray = Writer().apply(block).toBytes()
    fun box(name: String, vararg parts: ByteArray): ByteArray = build {
        i32(8 + parts.sumOf { it.size }); text(name); parts.forEach { raw(it) }
    }
    fun full(name: String, version: Int, flags: Int, data: ByteArray): ByteArray =
        box(name, build { i32((version shl 24) or flags); raw(data) })
    fun concat(vararg bytes: ByteArray): ByteArray = build { bytes.forEach { raw(it) } }
}