package com.example.fivethreeonelifter

data class AppPalette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val input: Int,
    val text: Int,
    val muted: Int,
    val border: Int,
    val primary: Int,
    val onPrimary: Int,
    val primarySoft: Int,
    val tealSoft: Int,
    val danger: Int,
    val dangerSoft: Int,
    val pr: Int,
    val prSoft: Int,
    val heatmapEmpty: Int,
    val heatmapLow: Int,
    val heatmapMedium: Int
) {
    companion object {
        val LIGHT = AppPalette(
            false, 0xFFF6F7FB.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF1C1F2A.toInt(), 0xFF696F80.toInt(), 0xFFE5E7EF.toInt(),
            0xFF5D4FDB.toInt(), 0xFFFFFFFF.toInt(), 0xFFEEEBFF.toInt(),
            0xFFE5F9F5.toInt(), 0xFFB83B48.toInt(), 0xFFFFEBED.toInt(),
            0xFFAE6800.toInt(), 0xFFFFF3D0.toInt(), 0xFFE8E9F1.toInt(),
            0xFFCEC6FF.toInt(), 0xFF8B7BEE.toInt()
        )
        val DARK = AppPalette(
            true, 0xFF111622.toInt(), 0xFF1C2231.toInt(), 0xFF151B28.toInt(),
            0xFFEFF1F8.toInt(), 0xFFABB2C6.toInt(), 0xFF3A4254.toInt(),
            0xFFFF69B4.toInt(), 0xFF24101D.toInt(), 0xFF482238.toInt(),
            0xFF183A38.toInt(), 0xFFFFADB3.toInt(), 0xFF48272C.toInt(),
            0xFFFFD77C.toInt(), 0xFF3C321C.toInt(), 0xFF303749.toInt(),
            0xFF70405C.toInt(), 0xFFC45691.toInt()
        )
    }
}
