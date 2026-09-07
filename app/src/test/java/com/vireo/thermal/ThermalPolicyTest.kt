package com.vireo.thermal

import com.vireo.thermal.ThermalGovernor.Companion.BATTERY_ECO_C
import com.vireo.thermal.ThermalGovernor.Companion.BATTERY_PAUSE_C
import com.vireo.thermal.ThermalGovernor.Companion.HEADROOM_ECO
import com.vireo.thermal.ThermalGovernor.Companion.HEADROOM_PAUSE
import com.vireo.thermal.ThermalGovernor.Companion.STATUS_LIGHT
import com.vireo.thermal.ThermalGovernor.Companion.STATUS_MODERATE
import com.vireo.thermal.ThermalGovernor.Companion.STATUS_NONE
import com.vireo.thermal.ThermalGovernor.Companion.STATUS_SEVERE
import com.vireo.thermal.ThermalGovernor.Companion.classify
import org.junit.Assert.assertEquals
import org.junit.Test

private fun tier(
    status: Int = STATUS_NONE,
    headroom: Float = Float.NaN,
    batteryC: Float = Float.NaN,
    eco: Boolean = false,
    override: ThermalTier? = null,
) = classify(status, headroom, batteryC, eco, override).first

class ThermalPolicyTest {

    @Test fun coolAndIdle_isNormal() {
        assertEquals(ThermalTier.NORMAL, tier(status = STATUS_LIGHT, headroom = 0.3f, batteryC = 31f))
    }

    @Test fun nanSignals_areIgnored() {
        assertEquals(ThermalTier.NORMAL, tier(status = STATUS_NONE, headroom = Float.NaN, batteryC = Float.NaN))
    }

    @Test fun moderateThermalStatus_isEco() {
        assertEquals(ThermalTier.ECO, tier(status = STATUS_MODERATE))
    }

    @Test fun severeThermalStatus_isPause() {
        assertEquals(ThermalTier.PAUSE, tier(status = STATUS_SEVERE))
    }

    @Test fun headroom_crossesEcoThenPause() {
        assertEquals(ThermalTier.NORMAL, tier(headroom = HEADROOM_ECO - 0.01f))
        assertEquals(ThermalTier.ECO, tier(headroom = HEADROOM_ECO + 0.01f))
        assertEquals(ThermalTier.PAUSE, tier(headroom = HEADROOM_PAUSE + 0.01f))
    }

    @Test fun batteryTemp_crossesEcoThenPause() {
        assertEquals(ThermalTier.NORMAL, tier(batteryC = BATTERY_ECO_C - 1f))
        assertEquals(ThermalTier.ECO, tier(batteryC = BATTERY_ECO_C + 1f))
        assertEquals(ThermalTier.PAUSE, tier(batteryC = BATTERY_PAUSE_C + 1f))
    }

    @Test fun ecoModeToggle_forcesAtLeastEco() {
        assertEquals(ThermalTier.ECO, tier(eco = true))
        // but a PAUSE condition still wins over eco-mode
        assertEquals(ThermalTier.PAUSE, tier(status = STATUS_SEVERE, eco = true))
    }

    @Test fun debugOverride_wins() {
        assertEquals(ThermalTier.PAUSE, tier(status = STATUS_NONE, headroom = 0.1f, override = ThermalTier.PAUSE))
        assertEquals(ThermalTier.NORMAL, tier(status = STATUS_SEVERE, override = ThermalTier.NORMAL))
    }
}
