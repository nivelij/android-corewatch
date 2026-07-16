package com.corewatch.monitor

import android.os.Build

/**
 * Best-effort mapping from the raw [Build.SOC_MODEL] codename (e.g. "SM8550") to a
 * human-friendly marketing name (e.g. "Snapdragon 8 Gen 2").
 *
 * The list is intentionally partial — SoC codenames are numerous and undocumented. When a
 * codename is not known we return `null` and the UI falls back to showing the raw model, so
 * the app is still useful (and honest) on unrecognised hardware.
 */
object Soc {

    private val marketingByCodename: Map<String, String> = mapOf(
        // Qualcomm Snapdragon
        "SM8750" to "Snapdragon 8 Elite",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8350" to "Snapdragon 888",
        "SM8250" to "Snapdragon 865",
        "SM8150" to "Snapdragon 855",
        "SM7675" to "Snapdragon 7+ Gen 3",
        "SM7550" to "Snapdragon 7 Gen 3",
        "SM7450" to "Snapdragon 7 Gen 1",
        "SM7325" to "Snapdragon 778G",
        "SM7250" to "Snapdragon 765G",
        "SM6450" to "Snapdragon 6 Gen 1",
        "SM6375" to "Snapdragon 695",
        "SM6225" to "Snapdragon 680",
        "SM6115" to "Snapdragon 662",
        // MediaTek Dimensity / Helio
        "MT6991" to "Dimensity 9400",
        "MT6989" to "Dimensity 9300",
        "MT6985" to "Dimensity 9200",
        "MT6983" to "Dimensity 9000",
        "MT6897" to "Dimensity 8300",
        "MT6896" to "Dimensity 8200",
        "MT6895" to "Dimensity 8100",
        "MT6886" to "Dimensity 7200",
        "MT6877" to "Dimensity 900",
        "MT6833" to "Dimensity 700",
        // Google Tensor
        "GS101" to "Google Tensor",
        "GS201" to "Google Tensor G2",
        "zuma" to "Google Tensor G3",
        "zumapro" to "Google Tensor G4",
        // Samsung Exynos
        "s5e9945" to "Exynos 2400",
        "s5e9925" to "Exynos 2200",
        "s5e9840" to "Exynos 2100",
    )

    /** Returns a marketing name for the SoC, or `null` if the codename is not recognised. */
    fun marketingName(socManufacturer: String?, socModel: String?): String? {
        val model = socModel?.trim().orEmpty()
        if (model.isEmpty() || model.equals(Build.UNKNOWN, ignoreCase = true)) return null
        marketingByCodename[model]?.let { return it }
        // Case-insensitive fallback (some vendors report lower/upper variants).
        return marketingByCodename.entries
            .firstOrNull { it.key.equals(model, ignoreCase = true) }
            ?.value
    }
}
