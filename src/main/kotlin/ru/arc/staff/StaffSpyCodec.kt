package ru.arc.staff

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.redis.safety.RedisWireCodec
import java.util.UUID

internal data class StaffSpyEvent(
    val v: Int, val id: String, val type: String, val senderUuid: String, val senderName: String,
    val targetUuid: String?, val targetName: String?, val content: String, val createdAt: Long,
)

internal data class StaffConsoleEvent(val v: Int, val id: String, val command: String, val actor: String, val createdAt: Long)

internal object StaffSpyCodecs {
    private val common = setOf("v", "id", "type", "senderUuid", "senderName", "content", "createdAt")
    val spy: RedisWireCodec<StaffSpyEvent> = byteBounded(BoundedJsonCodec(Gson(), StaffSpyEvent::class.java,
        object : JsonRootContract {
            override fun validate(value: com.google.gson.JsonElement) {
                val obj = value.asJsonObject
                val expected = if (obj.get("type")?.asString == "chat") common + setOf("targetUuid", "targetName") else common
                require(obj.keySet() == expected)
                exactNumbers(obj)
                obj.entrySet().forEach { (key, item) ->
                    if (key in setOf("v", "createdAt")) require(item.isJsonPrimitive && item.asJsonPrimitive.isNumber)
                    else require(item.isJsonNull && key in setOf("targetUuid", "targetName") || item.isJsonPrimitive && item.asJsonPrimitive.isString)
                }
            }
        }, JsonResourceBounds(8192, 2, 12, 20, 2048)) { value ->
        require(value.v == 1 && canonicalUuid(value.id) && canonicalUuid(value.senderUuid))
        require(value.type in setOf("chat", "command") && value.senderName.matches(Regex("[A-Za-z0-9_]{1,16}")))
        require(value.content.length in 1..2048 && value.content.none(StaffCommands::unsafeLineCharacter) && value.createdAt > 0)
        require(value.targetName == null || value.targetName.matches(Regex("[A-Za-z0-9_]{1,16}")))
        require(value.targetUuid == null || canonicalUuid(value.targetUuid))
        require(value.targetName != null || value.targetUuid == null)
    })
    val console: RedisWireCodec<StaffConsoleEvent> = byteBounded(BoundedJsonCodec(Gson(), StaffConsoleEvent::class.java,
        object : JsonRootContract {
            override fun validate(value: com.google.gson.JsonElement) {
                val obj = value.asJsonObject
                require(obj.keySet() == setOf("v", "id", "command", "actor", "createdAt"))
                exactNumbers(obj)
                listOf("id", "command", "actor").forEach { key ->
                    require(obj[key].isJsonPrimitive && obj[key].asJsonPrimitive.isString)
                }
            }
        },
        JsonResourceBounds(8192, 2, 5, 12, 512)) { value ->
        require(value.v == 1 && canonicalUuid(value.id) && (value.actor == "CONSOLE" || canonicalUuid(value.actor)))
        require(StaffCommands.validLine(value.command) && !StaffCommands.sensitive(value.command) && value.createdAt > 0)
    })
    private fun exactNumbers(obj: com.google.gson.JsonObject) {
        listOf("v", "createdAt").forEach { key ->
            require(obj[key].isJsonPrimitive && obj[key].asJsonPrimitive.isNumber)
            obj[key].asBigDecimal.longValueExact()
        }
    }
    private fun <T : Any> byteBounded(codec: RedisWireCodec<T>): RedisWireCodec<T> = object : RedisWireCodec<T> {
        override fun encode(value: T) = codec.encode(value).also { require(it.toByteArray(Charsets.UTF_8).size <= 8192) }
        override fun decode(raw: String): T {
            require(raw.length <= 8192)
            require(raw.toByteArray(Charsets.UTF_8).size <= 8192)
            return codec.decode(raw)
        }
    }
    fun canonicalUuid(raw: String): Boolean = runCatching { UUID.fromString(raw).toString() == raw }.getOrDefault(false)
    fun current(createdAt: Long, now: Long): Boolean = now - createdAt in -5_000..60_000
}
