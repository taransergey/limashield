package com.limashield.replay

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the replay bench (DR spec §7.2) over real field recordings.
 *
 * Field recordings hold the rider's real coordinates, so they are never committed;
 * point the bench at a local directory of raw-*.csv files:
 *   gradlew testDebugUnitTest --tests "com.limashield.replay.ReplayTest" -Plimashield.replay.dir=<dir>
 * Without the property the test is skipped (CI stays green).
 */
class ReplayTest {

    @Test
    fun `replay real recordings`() {
        val dir = System.getProperty("limashield.replay.dir").orEmpty()
        assumeTrue("no replay dir given — skipping", dir.isNotBlank() && File(dir).isDirectory)

        val out = StringBuilder()
        val allMetrics = mutableListOf<ReplayHarness.Metrics>()
        File(dir).listFiles { f -> f.name.startsWith("raw-") && f.name.endsWith(".csv") }!!
            .sortedBy { it.name }
            .forEach { file ->
                val fixes = ReplayHarness.parseRawCsv(file)
                val segments = ReplayHarness.cleanSegments(fixes)
                out.appendLine("${file.parentFile.name}/${file.name}: ${fixes.size} fixes, ${segments.size} clean riding segments")
                segments.forEachIndexed { idx, seg ->
                    for (mask in listOf(15, 30, 60, 120)) {
                        for (mode in ReplayHarness.ImuMode.entries) {
                            for (netLike in listOf(true, false)) {
                                val m = ReplayHarness.replaySegment(
                                    "${file.parentFile.name}/${file.name}#$idx", seg, mask, mode, netLike,
                                )
                                if (m.evalPoints >= 20) {
                                    allMetrics += m
                                    out.appendLine("  $m")
                                }
                            }
                        }
                    }
                }
            }
        println(out)
        File(dir, "replay-metrics.txt").writeText(out.toString())
        assumeTrue("no usable segments found", allMetrics.isNotEmpty())
    }
}
