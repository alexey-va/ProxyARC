package ru.arc.ai.routing.router

data class RouteDecision(
    val intent: RouteIntent,
    val confidence: Double,
    val reason: String,
    val raw: String,
    val model: String? = null,
    val parseOk: Boolean = true,
)
