package com.ontop.app

import org.junit.Assert.*
import org.junit.Test

class NotePercentagesTest {
    @Test fun twelveSegmentsShowExactPercentageIncludingPartialLastSegment() {
        for (value in listOf(0, 1, 50, 99, 100)) {
            val sweeps = importanceSweeps(value)
            assertEquals(12, sweeps.size)
            assertTrue(sweeps.all { it in 0f..25f })
            assertEquals(value / 100f, sweeps.sum() / 300f, .00001f)
            assertTrue(sweeps.zipWithNext().all { (a, b) -> a >= b })
        }
        assertEquals(3f, importanceSweeps(1).first(), .0001f)
        assertEquals(22f, importanceSweeps(99).last(), .0001f)
    }
    @Test fun inputRejectsEmptyNonIntegersAndOutOfRangeButKeepsEndpoints() {
        listOf("", " ", "abc", "50.5", "-1", "101", "99999999999999").forEach { assertNull(it, parsePercentage(it)) }
        listOf(0, 1, 50, 99, 100).forEach { assertEquals(it, parsePercentage(it.toString())) }
    }
    @Test fun dialStartsAtNoonAndMovesClockwiseWithoutWrappingAtEndpoints() {
        assertEquals(0f, clockwiseDegrees(0f, -1f), .001f)
        assertEquals(90f, clockwiseDegrees(1f, 0f), .001f)
        assertEquals(180f, clockwiseDegrees(0f, 1f), .001f)
        assertEquals(270f, clockwiseDegrees(-1f, 0f), .001f)
        assertEquals(25f, movedPercentage(0f, 0f, 90f), .001f)
        assertEquals(100f, movedPercentage(99f, 356.4f, 3.6f), .001f)
        assertEquals(0f, movedPercentage(0f, 0f, 356.4f), .001f)
        assertEquals(99f, movedPercentage(100f, 0f, 356.4f), .001f)
    }
}
