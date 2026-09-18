package com.example.fivethreeonelifter

import kotlin.math.round

object ProgramMath {
    data class SetPrescription(val percentage: Double, val reps: String)

    fun weekSets(week: Int): List<SetPrescription> = when (week) {
        1 -> listOf(
            SetPrescription(0.65, "5"),
            SetPrescription(0.75, "5"),
            SetPrescription(0.85, "5+")
        )
        2 -> listOf(
            SetPrescription(0.70, "3"),
            SetPrescription(0.80, "3"),
            SetPrescription(0.90, "3+")
        )
        3 -> listOf(
            SetPrescription(0.75, "5"),
            SetPrescription(0.85, "3"),
            SetPrescription(0.95, "1+")
        )
        else -> listOf(
            SetPrescription(0.40, "5"),
            SetPrescription(0.50, "5"),
            SetPrescription(0.60, "5")
        )
    }

    fun trainingMax(oneRepMax: Double, tmPercent: Double): Double = oneRepMax * tmPercent

    fun workingWeight(trainingMax: Double, percentage: Double, increment: Double): Double =
        roundToIncrement(trainingMax * percentage, increment)

    fun roundToIncrement(value: Double, increment: Double): Double {
        if (increment <= 0.0) return value
        return round(value / increment) * increment
    }
}
