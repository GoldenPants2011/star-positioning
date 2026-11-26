// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.content.Context
import com.dsh.starpos.astro.AstroTime
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarCatalog

/** 星表与索引的进程内缓存（按 J2000 / J2016.0 分别缓存；索引构建较慢，必须复用）。 */
object CatalogHolder {
    const val J2016_JD = 2457389.0

    data class Flavor(val key: String, val file: String, val refEpochJd: Double, val label: String)

    val J2000 = Flavor("j2000", "stars.bin", AstroTime.J2000, "HYG v41 · J2000")
    val J2016 = Flavor("j2016", "stars-j2016.bin", J2016_JD, "HYG v41 · J2016.0")

    private val catalogs = HashMap<String, StarCatalog>()
    private val indexes = HashMap<String, SkyIndex>()

    fun flavor(context: Context): Flavor {
        val key = context.getSharedPreferences("starpos", Context.MODE_PRIVATE)
            .getString("star_catalog", "j2000")
        return if (key == "j2016") J2016 else J2000
    }

    fun catalog(context: Context): StarCatalog {
        val f = flavor(context)
        synchronized(catalogs) {
            return catalogs[f.key] ?: Assets.loadCatalog(context, f.file, f.refEpochJd).also { catalogs[f.key] = it }
        }
    }

    fun index(context: Context): SkyIndex {
        val f = flavor(context)
        synchronized(indexes) {
            return indexes[f.key] ?: SkyIndex(catalog(context)).also { indexes[f.key] = it }
        }
    }
}
