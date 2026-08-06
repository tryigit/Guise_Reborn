package com.houvven.guise.db

import com.houvven.guise.xposed.config.ModuleConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

/** Versioned, forward-compatible transfer envelope for user-created profiles. */
@Serializable
data class TemplateBundle(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val templates: List<Template>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

object TemplateTransfer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(templates: List<Template>): String =
        json.encodeToString(TemplateBundle.serializer(), TemplateBundle(templates = templates))

    /**
     * Reads both the new bundle format and the legacy top-level JSON array used by older releases.
     * Invalid or ambiguous profiles are rejected before they can reach the Room database.
     */
    fun decode(content: String): List<Template> {
        val element = json.parseToJsonElement(content)
        val templates = if (element is JsonArray) {
            json.decodeFromJsonElement<List<Template>>(element)
        } else {
            val bundle = json.decodeFromJsonElement<TemplateBundle>(element)
            require(bundle.schemaVersion in 1..TemplateBundle.CURRENT_SCHEMA_VERSION) {
                "Unsupported template schema ${bundle.schemaVersion}"
            }
            bundle.templates
        }
        require(templates.isNotEmpty()) { "Template file contains no profiles" }
        return templates.map(::validate).distinctBy(Template::id)
    }

    private fun validate(template: Template): Template {
        require(template.id.isNotBlank()) { "Template id is missing" }
        require(template.name.isNotBlank()) { "Template name is missing" }
        require(template.type == Template.Type.COMMON || template.type == Template.Type.EXCLUSIVE) {
            "Unknown template type ${template.type}"
        }
        if (template.type == Template.Type.EXCLUSIVE) {
            require(!template.packageName.isNullOrBlank()) {
                "Exclusive template ${template.name} has no package name"
            }
        }
        // Parse once on import so malformed configuration blobs fail atomically instead of later.
        ModuleConfig.fromJson(template.configuration)
        return template.copy(
            name = template.name.trim(),
            description = template.description?.trim()?.takeIf(String::isNotEmpty),
            packageName = template.packageName?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}
