package ru.arc.xserver.adapters

import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class JsonSubtype(
    val clazz: KClass<*>,
    val name: String,
)
