package dev.skyward.rf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dot-layer stride must never be zero.
 *
 * It was: `(size + 899) / 900` is 0 for an empty layer, and `indices step 0` throws instead of
 * skipping. The draw loop catches its own failures so a bad projection costs a frame rather than
 * the app, which is why the throw was invisible: it deleted every marker drawn after the wardrive
 * dots - all satellites, all aircraft, and the GPS pin.
 */
class DrawStrideTest {

    @Test
    fun `an empty layer strides by one`() {
        assertEquals(1, drawStride(0, DotBudget))
    }

    @Test
    fun `a layer inside the budget is drawn whole`() {
        assertEquals(1, drawStride(1, DotBudget))
        assertEquals(1, drawStride(DotBudget / 2, DotBudget))
        assertEquals(1, drawStride(DotBudget, DotBudget))
    }

    @Test
    fun `a layer past the budget is sampled to fit it`() {
        assertEquals(2, drawStride(DotBudget + 1, DotBudget))
        assertEquals(2, drawStride(DotBudget * 2, DotBudget))
        assertEquals(6, drawStride(5_000, DotBudget))
    }

    @Test
    fun `every stride leaves at most the budget in view`() {
        for (count in 0..20_000) {
            val step = drawStride(count, DotBudget)
            val kept = (count + step - 1) / step
            assert(kept <= DotBudget) { "count=$count kept=$kept" }
        }
    }
}
