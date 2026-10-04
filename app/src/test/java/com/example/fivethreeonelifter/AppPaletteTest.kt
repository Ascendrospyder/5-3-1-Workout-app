package com.example.fivethreeonelifter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class AppPaletteTest {
    @Test fun darkActionsUseHotPink() {
        assertEquals(0xFFFF69B4.toInt(), AppPalette.DARK.primary)
    }

    @Test fun buttonLabelsMeetNormalTextContrast() {
        listOf(AppPalette.LIGHT, AppPalette.DARK).forEach { palette ->
            assertReadable(palette.onPrimary, palette.primary)
            assertReadable(palette.primary, palette.primarySoft)
            assertReadable(palette.primary, palette.surface)
            assertReadable(palette.danger, palette.dangerSoft)
        }
    }

    private fun assertReadable(foreground: Int, background: Int) {
        val a = luminance(foreground)
        val b = luminance(background)
        val contrast = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("Button text contrast $contrast must be at least 4.5:1", contrast >= 4.5)
    }

    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}
