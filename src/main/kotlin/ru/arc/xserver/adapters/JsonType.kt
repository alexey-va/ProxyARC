package ru.arc.xserver.adapters

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class JsonType(
    val property: String,
    vararg val subtypes: JsonSubtype,
)
