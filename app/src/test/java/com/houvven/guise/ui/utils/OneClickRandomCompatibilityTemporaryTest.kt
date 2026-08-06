package com.houvven.guise.ui.utils

import com.houvven.guise.module.preset.ResourcePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OneClickRandomCompatibilityTemporaryTest {

    private val presets = listOf(
        ResourcePreset("Android 10", "10|29"),
        ResourcePreset("Android 14", "14|34"),
        ResourcePreset("Android 17", "17|37"),
    )

    @Test
    fun selectedProfileNeverExceedsHostApi() {
        repeat(100) {
            val api = selectCompatibleAndroid(presets, deviceApi = 34)
                .value.substringAfter('|').toInt()
            assertTrue(api in 29..34)
        }
    }

    @Test
    fun android10HostAlwaysReceivesAndroid10Profile() {
        assertEquals("10|29", selectCompatibleAndroid(presets, deviceApi = 29).value)
    }

    @Test
    fun missingCompatiblePresetFailsInsteadOfReturningNewerApi() {
        assertThrows(IllegalStateException::class.java) {
            selectCompatibleAndroid(
                listOf(ResourcePreset("Android 14", "14|34")),
                deviceApi = 29,
            )
        }
    }
}
