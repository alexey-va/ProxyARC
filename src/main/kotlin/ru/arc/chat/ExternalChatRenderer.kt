package ru.arc.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.util.HSVLike
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.slf4j.LoggerFactory
import ru.arc.config.Config
import ru.arc.config.EmptyConfig
import ru.arc.config.ProxyConfigs
import ru.arc.util.TextUtils
import ru.arc.velocity.Velocity
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** LuckPerms display metadata used when formatting a linked in-game speaker. */
data class ExternalChatMeta(
    val prefix: String = "",
)

/**
 * Formats an external speaker for Minecraft chat while keeping the metadata lookup
 * asynchronous. The name and message are always inserted as literal Adventure components.
 *
 * The nickname tint mirrors ARC's `ChatMessageColorizer` and its `ChatMessageColorizerTest`:
 * lowercase-name FNV-1a gives one stable hue offset in both channel palettes.
 */
class ExternalChatRenderer(
    private val metaProvider: (UUID) -> CompletableFuture<ExternalChatMeta> = { uuid ->
        Velocity.luckpermsHook?.getChatMeta(uuid)
            ?: CompletableFuture.failedFuture(IllegalStateException("LuckPerms unavailable"))
    },
    private val configProvider: () -> Config = {
        Velocity.dataFolder?.let { ProxyConfigs.module(it, "chat-style.yml") } ?: EmptyConfig
    },
) {
    /**
     * Resolve optional metadata for an authenticated linked UUID, then render without waiting for
     * LuckPerms. A null UUID deliberately skips the provider so an unlinked name cannot request
     * another player's prefix.
     */
    public fun render(
        playerId: UUID?,
        playerName: String,
        message: Component,
        formatter: (Component, Component) -> Component,
    ): CompletableFuture<Component> {
        val style = ChatStyle.from(configProvider())
        val metadata = lookupMeta(playerId)
        return metadata.thenApply { meta ->
            val sender = senderComponent(meta.prefix, playerName, style.nameColor)
            val bodyColor =
                messageColor(
                    base = style.messageColor,
                    playerName = playerName,
                    enabled = style.variationEnabled,
                    amplitudeDegrees = style.hueAmplitudeDegrees,
                )
            formatter(sender, message.color(bodyColor))
        }
    }

    private fun lookupMeta(playerId: UUID?): CompletableFuture<ExternalChatMeta> {
        if (playerId == null) return CompletableFuture.completedFuture(ExternalChatMeta())

        val lookup =
            try {
                // Timeout a derived future so a slow provider cannot swallow chat and the provider
                // retains ownership of its original future.
                metaProvider(playerId).thenApply { it }.orTimeout(META_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (failure: Throwable) {
                CompletableFuture.failedFuture(failure)
            }

        return lookup.handle { meta, failure ->
            if (failure == null) {
                meta ?: ExternalChatMeta()
            } else {
                log.warn("Unable to resolve external chat prefix for linked player {}", playerId, unwrap(failure))
                ExternalChatMeta()
            }
        }
    }

    private fun senderComponent(
        prefixText: String,
        playerName: String,
        nameColor: TextColor,
    ): Component {
        val name = Component.text(playerName).color(nameColor)
        if (prefixText.isBlank()) return name

        val prefix = colorPrivateUseGlyphs(parseAffix(prefixText))
        val visiblePrefix = TextUtils.plain(prefix)
        if (visiblePrefix.isBlank()) return name

        val separator =
            if (visiblePrefix.last().isWhitespace()) Component.empty() else Component.space()
        return Component.empty().append(prefix).append(separator).append(name)
    }

    private fun parseAffix(text: String): Component =
        if (LEGACY_CODE_PATTERN.containsMatchIn(text)) {
            LegacyComponentSerializer.legacyAmpersand().deserialize(text.replace('§', '&'))
        } else {
            TextUtils.mm(text)
        }

    private fun colorPrivateUseGlyphs(component: Component): Component =
        component.replaceText(
            TextReplacementConfig.builder()
                .match(PRIVATE_USE_GLYPH_PATTERN)
                .replacement { _, matched -> matched.color(WHITE) }
                .build(),
        )

    private fun unwrap(failure: Throwable): Throwable =
        (failure as? CompletionException)?.cause ?: failure

    private data class ChatStyle(
        val nameColor: TextColor,
        val messageColor: TextColor,
        val variationEnabled: Boolean,
        val hueAmplitudeDegrees: Double,
    ) {
        companion object {
            fun from(config: Config): ChatStyle =
                ChatStyle(
                    nameColor = color(config.string("name-color", DEFAULT_NAME_COLOR_HEX), DEFAULT_NAME_COLOR),
                    messageColor = color(config.string("message-color", DEFAULT_MESSAGE_COLOR_HEX), DEFAULT_MESSAGE_COLOR),
                    variationEnabled = config.bool("message-color-variation.enabled", true),
                    hueAmplitudeDegrees =
                        config
                            .double("message-color-variation.hue-amplitude-degrees", DEFAULT_HUE_AMPLITUDE_DEGREES)
                            .takeIf(Double::isFinite)
                            ?.coerceIn(0.0, MAX_HUE_AMPLITUDE_DEGREES)
                            ?: DEFAULT_HUE_AMPLITUDE_DEGREES,
                )

            private fun color(
                value: String,
                fallback: TextColor,
            ): TextColor = TextColor.fromHexString(value.trim()) ?: fallback
        }
    }

    private companion object {
        const val META_TIMEOUT_SECONDS = 3L
        const val DEFAULT_HUE_AMPLITUDE_DEGREES = 30.0
        const val MAX_HUE_AMPLITUDE_DEGREES = 30.0
        const val DEFAULT_NAME_COLOR_HEX = "#72B8E6"
        const val DEFAULT_MESSAGE_COLOR_HEX = "#CFE7FF"
        val DEFAULT_NAME_COLOR: TextColor = TextColor.color(0x72B8E6)
        val DEFAULT_MESSAGE_COLOR: TextColor = TextColor.color(0xCFE7FF)
        val WHITE: TextColor = TextColor.color(0xFFFFFF)
        val log = LoggerFactory.getLogger(ExternalChatRenderer::class.java)
        val PRIVATE_USE_GLYPH_PATTERN: Pattern = Pattern.compile("[\\uE000-\\uF8FF]")
        val LEGACY_CODE_PATTERN =
            Regex("(?i)(?:[&§]x(?:[&§][0-9a-f]){6}|[&§]#[0-9a-f]{6}|[&§][0-9a-fk-or])")

        /** Mirrors ARC's `ChatMessageColorizer` FNV-1a hue shift for same-nickname parity. */
        fun messageColor(
            base: TextColor,
            playerName: String,
            enabled: Boolean,
            amplitudeDegrees: Double,
        ): TextColor {
            val amplitude =
                amplitudeDegrees
                    .takeIf(Double::isFinite)
                    ?.coerceIn(0.0, MAX_HUE_AMPLITUDE_DEGREES)
                    ?: DEFAULT_HUE_AMPLITUDE_DEGREES
            if (!enabled || amplitude == 0.0) return base

            val hsv = HSVLike.fromRGB(base.red(), base.green(), base.blue())
            val shiftedHue = wrapHue(hsv.h().toDouble() + speakerOffset(playerName) * amplitude / 360.0)
            return TextColor.color(HSVLike.hsvLike(shiftedHue.toFloat(), hsv.s(), hsv.v()))
        }

        fun speakerOffset(playerName: String): Double {
            var hash = FNV_OFFSET_BASIS
            playerName.lowercase(Locale.ROOT).forEach { character ->
                hash = hash xor character.code
                hash *= FNV_PRIME
            }
            val unit = (hash.toLong() and UNSIGNED_INT_MASK) / UNSIGNED_INT_MAX
            return unit * 2.0 - 1.0
        }

        fun wrapHue(hue: Double): Double = ((hue % 1.0) + 1.0) % 1.0

        const val FNV_OFFSET_BASIS = -2_128_831_035
        const val FNV_PRIME = 16_777_619
        const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL
        const val UNSIGNED_INT_MAX = 4_294_967_295.0
    }
}
