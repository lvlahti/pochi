package io.github.lvlahti.pochi

/**
 * Conservative AIMD bitrate adaptation. The encoder is reconfigured by MediaCodec.setParameters
 * without tearing down the projection or re-requesting capture permission.
 * One congestion sample reduces throughput quickly; recovery needs three clean samples.
 */
class AutoQuality(private val ceilingKbps: Int, startKbps: Int = ceilingKbps) {
    val floorKbps: Int = minOf(ceilingKbps, 350).coerceAtLeast(100)
    var bitrateKbps: Int = startKbps.coerceIn(floorKbps, ceilingKbps)
        private set
    private var cleanSamples = 0

    fun update(congested: Boolean): Int {
        if (congested) {
            cleanSamples = 0
            bitrateKbps = (bitrateKbps * 0.72).toInt().coerceAtLeast(floorKbps)
        } else if (++cleanSamples >= 3) {
            cleanSamples = 0
            bitrateKbps = (bitrateKbps + maxOf(100, (bitrateKbps * 0.1).toInt()))
                .coerceAtMost(ceilingKbps)
        }
        return bitrateKbps
    }
}
