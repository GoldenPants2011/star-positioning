// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.core

import kotlin.math.sqrt

/** 误差预算一行（需求 §8：分解必须能在报告中复现）。 */
class BudgetRow(
    val source: String,
    val typical: String,
    val groundMeters: Double,
    val note: String,
)

object ErrorBudget {

    /**
     * 把各误差源折算到地面（米）。这一实现只覆盖本期真正会进入解算的项；
     * 未实现项（畸变残差、地平线）标注为"未进入本期解算"。
     */
    fun compute(
        attitudeSigmaDeg: Double,
        gravitySigmaDeg: Double,
        timeUncertaintySec: Double,
        refractionResidualArcsec: Double = 2.0,
        latitudeDeg: Double = 0.0,
    ): List<BudgetRow> {
        val meterPerDeg = 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(latitudeDeg)).coerceAtLeast(0.05)
        val rows = ArrayList<BudgetRow>()
        rows.add(
            BudgetRow(
                "星点质心/姿态", "%.2f″".format(attitudeSigmaDeg * 3600), attitudeSigmaDeg * meterPerDeg,
                "已计入：由匹配残差估计的 1σ",
            ),
        )
        rows.add(
            BudgetRow(
                "加速度计倾角（静止）", "%.3f°".format(gravitySigmaDeg), gravitySigmaDeg * meterPerDeg,
                "已计入：静止样本角散布",
            ),
        )
        rows.add(
            BudgetRow(
                "UTC 误差", "%.0f ms".format(timeUncertaintySec * 1000),
                timeUncertaintySec * 465.1 / cosLat,
                "已计入：经度方向按 1/cosφ 放大",
            ),
        )
        rows.add(
            BudgetRow(
                "大气折射（低空）", "%.1f″".format(refractionResidualArcsec),
                (refractionResidualArcsec / 3600.0) * meterPerDeg,
                "已计入：Bennett 模型残差（未用真实气压温度场）",
            ),
        )
        rows.add(BudgetRow("畸变残差", "0.1–1′", 200.0, "未进入本期解算：需要逐台标定 + 自标定"))
        rows.add(BudgetRow("外参误差", "0.1°", 11_000.0, "未进入本期解算：M2 引入 camera↔IMU 标定"))
        rows.add(BudgetRow("地平线增强（T2）", "—", 0.0, "未实现：M4"))
        return rows
    }

    /** 合成总误差（1σ，米，东西/南北分别给出）。 */
    fun totalSigmaMeters(rows: List<BudgetRow>): Double =
        sqrt(rows.filter { it.groundMeters > 0 }.sumOf { it.groundMeters * it.groundMeters })
}
