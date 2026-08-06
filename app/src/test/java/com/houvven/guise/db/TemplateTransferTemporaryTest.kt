package com.houvven.guise.db

import com.houvven.guise.xposed.config.ModuleConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TemplateTransferTemporaryTest {

    private val template = Template(
        id = "profile-1",
        name = " Android 10 profile ",
        type = Template.Type.COMMON,
        configuration = ModuleConfig(
            brand = "Google",
            model = "Pixel 4",
            androidVersion = "10",
            sdkInt = 29,
        ).toJson(),
    )

    @Test
    fun versionedBundleRoundTripsAndNormalizesMetadata() {
        val decoded = TemplateTransfer.decode(TemplateTransfer.encode(listOf(template)))

        assertEquals(1, decoded.size)
        assertEquals("Android 10 profile", decoded.single().name)
        assertEquals(29, ModuleConfig.fromJson(decoded.single().configuration).sdkInt)
    }

    @Test
    fun legacyTopLevelArrayRemainsImportable() {
        val legacyJson = Json.encodeToString(listOf(template))

        assertEquals("profile-1", TemplateTransfer.decode(legacyJson).single().id)
    }

    @Test
    fun malformedConfigurationIsRejectedAtomically() {
        val invalid = template.copy(configuration = "{not-json}")

        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.decode(TemplateTransfer.encode(listOf(invalid)))
        }
    }
}
