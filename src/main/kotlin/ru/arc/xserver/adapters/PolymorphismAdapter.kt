package ru.arc.xserver.adapters

import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

class PolymorphismAdapter<T> : JsonSerializer<T>, JsonDeserializer<T> {
    private val gson = Gson()

    override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): T {
        try {
            val typeClass = extract(type as Class<*>)
            val jsonType = typeClass.getDeclaredAnnotation(JsonType::class.java)
                ?: throw JsonParseException("Missing @JsonType on $typeClass")
            val property = json.asJsonObject.get(jsonType.property).asString
            val subType = jsonType.subtypes
                .firstOrNull { it.name == property }
                ?.clazz
                ?.java
                ?: throw IllegalArgumentException("Unknown subtype: $property")
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(json, subType) as T
        } catch (e: Exception) {
            throw JsonParseException("Failed deserialize json", e)
        }
    }

    override fun serialize(src: T, type: Type, context: JsonSerializationContext): JsonElement {
        val parentAbstractClass = extract(type as Class<*>)
        if (!parentAbstractClass.isAnnotationPresent(JsonType::class.java)) {
            return gson.toJsonTree(src)
        }
        val jsonType = parentAbstractClass.getDeclaredAnnotation(JsonType::class.java)
            ?: return gson.toJsonTree(src)
        val property = jsonType.property

        for (subtype in jsonType.subtypes) {
            if (subtype.clazz.java.isInstance(src)) {
                val jsonObject = gson.toJsonTree(src).asJsonObject
                jsonObject.addProperty(property, subtype.name)
                return jsonObject
            }
        }
        return gson.toJsonTree(src)
    }

    private companion object {
        fun extract(type: Class<*>): Class<*> {
            var parentAbstractClass = type
            while (!parentAbstractClass.isAnnotationPresent(JsonType::class.java)) {
                val superClass = parentAbstractClass.superclass ?: break
                parentAbstractClass = superClass
            }
            return parentAbstractClass
        }
    }
}
