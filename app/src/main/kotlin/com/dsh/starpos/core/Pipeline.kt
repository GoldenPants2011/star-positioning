// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.core

import com.dsh.starpos.astro.Detection
import com.dsh.starpos.astro.GravityEstimate
import com.dsh.starpos.astro.ImageQuality
import com.dsh.starpos.astro.PlateSolution
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionResult
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import kotlin.math.min

/**
 * 解算流程编排：质量门控 → 星图识别 → 姿态 → 位置 → 分级/置信度（需求 FR-02/04/05/08/09/10）。
 *
 * 设计原则（§12 反模式）：任何一步不达标都返回 NO_FIX 并给出原因，绝不"猜"。
 */
class Pipeline(
    private val solver: PlateSolver,
) {
    class Input(
        val detections: List<Detection>,
        val quality: ImageQuality,
        val gravity: GravityEstimate,
        val clock: ClockStatus,
        val context: SkyContext,
        val mode: SolveMode = SolveMode.STAR,
        val pressureHpa: Double = 1010.0,
        val tempC: Double = 10.0,
        val ephemerisAgeDays: Double = 0.0,
    )

    class Output(
        val result: SolveResult,
        val plate: PlateSolution?,
        val position: PositionResult?,
        val budget: List<BudgetRow>,
    )

    fun solve(input: Input): Output {
        val warnings = ArrayList<String>(input.clock.warnings)
        if (input.quality.starCount < 8) warnings.add("TOO_FEW_STARS")
        if (input.quality.cloudCover > 0.7) warnings.add("CLOUD_COVER_TOO_HIGH")
        if (input.quality.maxPsfAspect > 3.0) warnings.add("TRAILING_DETECTED")
        if (input.quality.saturatedCount > 0) warnings.add("SATURATED_STARS")
        if (!input.gravity.isStatic) warnings.add("NOT_STATIC")
        warnings.addAll(input.clock.warnings)

        fun noFix(
            reason: String,
            plate: PlateSolution? = null,
            pos: PositionResult? = null,
        ): Output {
            val result = SolveResult(
                status = "NO_FIX",
                tier = null,
                mode = input.mode,
                timestampUtcMs = com.dsh.starpos.astro.AstroTime.unixMillisFromJd(input.context.jdUtc),
                timeUncertaintyMs = input.clock.clockUncertaintyMs,
                lastSyncUtcMs = input.clock.lastSyncUtcMs,
                offlineHours = input.clock.offlineHours,
                ephemerisAgeDays = input.ephemerisAgeDays,
                position = null,
                ellipse = null,
                attitude = plate?.rotation,
                attitudeSigmaDeg = plate?.attitudeSigmaDeg ?: 0.0,
                gravitySigmaDeg = input.gravity.sigmaDeg,
                starsUsed = plate?.matchedStars ?: 0,
                cloudCover = input.quality.cloudCover,
                confidence = 0.0,
                warnings = warnings.distinct(),
                reason = reason,
            )
            return Output(result, plate, pos, emptyList())
        }

        // 1) 质量门控（§3 失败定义）
        if (input.detections.size < 8) return noFix("TOO_FEW_STARS")
        if (input.quality.cloudCover > 0.7) return noFix("CLOUD_COVER_TOO_HIGH")
        if (input.quality.maxPsfAspect > 3.0) return noFix("TRAILING_DETECTED")

        // 2) 星图识别与姿态（用重力给出的天顶先做大气折射反解，FR-05）
        val plate = solver.solve(
            input.detections,
            upCam = if (input.gravity.valid) input.gravity.upCam else null,
            pressureHpa = input.pressureHpa,
            tempC = input.tempC,
        ) ?: return noFix("PLATE_SOLVE_FAILED")
        if (plate.matchedStars < 5) return noFix("TOO_FEW_MATCHED_STARS", plate)

        val att3Sigma = 3 * plate.attitudeSigmaDeg
        if (att3Sigma > 0.02) {
            return noFix("ATTITUDE_COVARIANCE_EXCEEDED", plate)
        }
        // T0 姿态已达标，但位置还需要重力与时间
        if (!input.gravity.isStatic) {
            return noFix("NOT_STATIC", plate)
        }
        if (input.clock.stale) {
            return noFix("STALE_TIME", plate)
        }

        // 3) 位置
        val timeSec = input.clock.clockUncertaintyMs / 1000.0
        val pos = PositionSolver.solve(
            rotation = plate.rotation,
            upCam = input.gravity.upCam,
            context = input.context,
            gravitySigmaDeg = input.gravity.sigmaDeg,
            attitudeSigmaDeg = plate.attitudeSigmaDeg,
            timeUncertaintySec = timeSec,
        )
        warnings.addAll(pos.warnings)

        // 4) 置信度（0–1；未经外场可靠性校准，见 FR-09）
        var conf = 0.30
        conf += 0.35 * min(1.0, (plate.matchedStars - 5) / 10.0)
        conf += 0.20 * (1.0 - input.quality.cloudCover.coerceIn(0.0, 1.0))
        conf += 0.15 * (1.0 - min(1.0, att3Sigma / 0.02))
        if (input.quality.saturatedCount > 0) conf -= 0.05
        if (input.quality.maxPsfAspect > 2.0) conf -= 0.05
        conf = conf.coerceIn(0.0, 0.99)

        if (conf < 0.5) {
            return noFix("LOW_CONFIDENCE", plate)
        }

        val budget = ErrorBudget.compute(
            attitudeSigmaDeg = plate.attitudeSigmaDeg,
            gravitySigmaDeg = input.gravity.sigmaDeg,
            timeUncertaintySec = timeSec,
            latitudeDeg = pos.fix?.latDeg ?: 0.0,
        )
        val result = SolveResult(
            status = "FIX",
            tier = "T1",
            mode = input.mode,
            timestampUtcMs = com.dsh.starpos.astro.AstroTime.unixMillisFromJd(input.context.jdUtc),
            timeUncertaintyMs = input.clock.clockUncertaintyMs,
            lastSyncUtcMs = input.clock.lastSyncUtcMs,
            offlineHours = input.clock.offlineHours,
            ephemerisAgeDays = input.ephemerisAgeDays,
            position = pos.fix,
            ellipse = pos.ellipse,
            attitude = plate.rotation,
            attitudeSigmaDeg = plate.attitudeSigmaDeg,
            gravitySigmaDeg = input.gravity.sigmaDeg,
            starsUsed = plate.matchedStars,
            cloudCover = input.quality.cloudCover,
            confidence = conf,
            warnings = warnings.distinct(),
        )
        return Output(result, plate, pos, budget)
    }
}

