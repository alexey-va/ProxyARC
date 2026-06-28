package ru.arc.ai.routing.router

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

object RouterJsonParser {
    private val gson = Gson()
    private val intentInJson = Regex(""""intent"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): RouteDecision {
        val text = stripMarkdown(raw)
        if (text.isBlank()) {
            return failed(raw, "empty response")
        }
        try {
            return parseJsonObject(text, raw)
        } catch (_: JsonSyntaxException) {
            val match = intentInJson.find(text)
            if (match != null) {
                val intent = RouteIntent.fromWire(match.groupValues[1]) ?: RouteIntent.SKIP
                return RouteDecision(
                    intent = intent,
                    confidence = 0.5,
                    reason = "partial_json",
                    raw = raw,
                    parseOk = false,
                )
            }
            return failed(raw, "parse_failed")
        }
    }

    private fun parseJsonObject(text: String, raw: String): RouteDecision {
        val tree = gson.fromJson(text, Map::class.java)
        val intentRaw = tree["intent"]?.toString()?.trim().orEmpty()
        val intent = RouteIntent.fromWire(intentRaw) ?: RouteIntent.SKIP
        val confidence =
            when (val value = tree["confidence"]) {
                is Number -> value.toDouble().coerceIn(0.0, 1.0)
                is String -> value.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.5
                else -> 0.5
            }
        val reason = tree["reason"]?.toString()?.trim().orEmpty()
        return RouteDecision(
            intent = intent,
            confidence = confidence,
            reason = reason,
            raw = raw,
            parseOk = true,
        )
    }

    private fun stripMarkdown(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            if (text.endsWith("```")) {
                text = text.removeSuffix("```").trim()
            }
        }
        return text
    }

    private fun failed(raw: String, reason: String): RouteDecision =
        RouteDecision(
            intent = RouteIntent.SKIP,
            confidence = 0.0,
            reason = reason,
            raw = raw,
            parseOk = false,
        )
}
