package dev.continuum.fx.island

import androidx.compose.animation.core.FloatSpringSpec
import dev.continuum.fx.island.ui.wingPadding
import dev.continuum.fx.ui.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WingLayoutTest {
    @Test
    fun dismissingAWingNeverPassesNegativePaddingToCompose() {
        val motion = Motion.flow<Float>()
        val spring = FloatSpringSpec(motion.dampingRatio, motion.stiffness)
        var overshot = false
        // This is the exit spring used by both wings, including the frames after reaching zero.
        for (ms in 0..2000 step 8) {
            val presence = spring.getValueFromNanos(ms * 1_000_000L, 1f, 0f, 0f)
            if (presence < 0f) overshot = true
            val padding = wingPadding(24f, presence)
            assertTrue("Negative padding at ${ms}ms: $padding", padding >= 0f)
            assertTrue(padding <= 24f)
        }
        assertTrue("Exercise the undershoot that caused the phone crash", overshot)
    }

    @Test
    fun paddingHasNoJumpAtTheAnimationEndpoints() {
        assertEquals(0f, wingPadding(24f, -0.1f), 0f)
        assertEquals(0f, wingPadding(24f, 0f), 0f)
        assertEquals(12f, wingPadding(24f, 0.5f), 0f)
        assertEquals(24f, wingPadding(24f, 1.1f), 0f)
    }
}
