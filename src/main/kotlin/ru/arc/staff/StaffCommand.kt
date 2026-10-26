package ru.arc.staff

import java.util.Locale
import java.util.UUID

internal data class StaffIdentity(val uuid: UUID, val name: String)
internal data class StaffActor(
    val identity: StaffIdentity,
    val admin: Boolean,
    private val refresh: (() -> StaffActor?)? = null,
    val permission: (String) -> Boolean,
) {
    /** Re-evaluates loaded LP state without blocking or loading on the native command thread. */
    fun current(): StaffActor? = if (refresh == null) this else refresh.invoke()
}
internal data class StaffCommand(val server: String, val line: String, val permission: String?, val action: String)

/** Formal command grammar. User-controlled names/durations/reasons cannot become LiteBans switches. */
internal object StaffCommands {
    val SHORTCUTS = setOf("kick", "ban", "mute")

    fun parse(input: String): StaffCommand? {
        if (input.length !in 1..640 || input.any(::unsafeLineCharacter)) return null
        val text = input.trim()
        val tokens = text.split(Regex(" +"), limit = 4)
        return when (val action = tokens[0].lowercase(Locale.ROOT)) {
            "mute", "ban" -> {
                if (tokens.size != 4 || !NAME.matches(tokens[1]) || !DURATION.matches(tokens[2]) ||
                    !validReason(tokens[3])) return null
                val permanent = tokens[2] == "permanent"
                val command = if (permanent) action else "temp$action"
                val line = if (permanent) "$action ${tokens[1]} ${tokens[3]}" else "$command ${tokens[1]} ${tokens[2]} ${tokens[3]}"
                StaffCommand("proxy", line, "litebans.$command", action)
            }
            "kick" -> {
                val parts = text.split(Regex(" +"), limit = 3)
                if (parts.size != 3 || !NAME.matches(parts[1]) || !validReason(parts[2])) return null
                StaffCommand("proxy", "kick ${parts[1]} ${parts[2]}", "litebans.kick", action)
            }
            "unmute", "unban" -> {
                if (tokens.size != 2 || !NAME.matches(tokens[1])) return null
                StaffCommand("proxy", "$action ${tokens[1]}", "litebans.$action", action)
            }
            "command" -> {
                val parts = text.split(' ', limit = 3)
                if (parts.size != 3 || !SERVER.matches(parts[1])) return null
                val line = parts[2].trim().removePrefix("/")
                if (!validLine(line) || sensitive(line)) return null
                StaffCommand(parts[1], line, null, "command")
            }
            else -> null
        }
    }

    private fun validReason(reason: String): Boolean =
        reason.isNotBlank() && reason.length <= 256 && reason.split(' ').none { it.startsWith('-') }

    fun validLine(line: String): Boolean = line.length in 1..512 && line.none(::unsafeLineCharacter) && line.isNotBlank()

    fun unsafeLineCharacter(char: Char): Boolean = char.isISOControl() || char == '\u2028' || char == '\u2029'

    /** Auth credentials never enter remote command/history/spy surfaces, even for an administrator. */
    fun sensitive(line: String): Boolean {
        if (line.any(::unsafeLineCharacter)) return true
        val words = line.trim().removePrefix("/").lowercase(Locale.ROOT).split(Regex("\\s+"))
        val root = words.firstOrNull()?.substringAfter(':') ?: return true
        if (root in SENSITIVE) return true
        return root == "cmi" && words.getOrNull(1)?.removePrefix("/")?.substringAfter(':') in SENSITIVE
    }

    private val NAME = Regex("[A-Za-z0-9_]{1,16}")
    private val SERVER = Regex("[a-z0-9][a-z0-9_-]{0,47}")
    private val DURATION = Regex("(?:[1-9][0-9]{0,4}[smhdw]|permanent)")
    // Keep the mandatory credential/wrapper floor aligned with ARC SpyRelayPolicy.
    private val SENSITIVE = setOf(
        "login", "l", "register", "reg", "changepassword", "changepass", "password", "unregister", "2fa", "totp",
        "auth", "authme", "limboauth", "nlogin", "resetpassword", "resetpass", "setpassword", "setpass",
        "forcepassword", "forcelogin", "forceregister", "usermeta", "token", "secret", "apikey", "api-key",
        "execute", "sudo", "runas", "eval", "dispatch", "script", "skript", "denizen", "ex", "exs",
        "passwd", "pin", "premium", "cracked", "forcepasswd",
    )
}
