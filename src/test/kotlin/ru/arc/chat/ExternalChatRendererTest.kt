package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.config.EmptyConfig
import ru.arc.util.TextUtils
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ExternalChatRendererTest : FreeSpec({
    val playerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    "linked metadata is asynchronous and keeps the exact authenticated UUID" {
        val pending = CompletableFuture<ExternalChatMeta>()
        val renderer = renderer { uuid ->
            uuid shouldBe playerId
            pending
        }
        val result = renderer.render(playerId, "GrocerMC", Component.text("hello"), ::line)
        result.isDone shouldBe false
        pending.complete(ExternalChatMeta("&6[VIP]"))
        TextUtils.plain(result.join()) shouldBe "[VIP] GrocerMC » hello"
    }

    "unlinked display names cannot borrow a prefix" {
        val renderer = renderer { error("Unlinked names must never query LuckPerms") }
        val result = renderer.render(null, "GrocerMC", Component.text("hello"), ::line).join()
        TextUtils.plain(result) shouldBe "GrocerMC » hello"
    }

    "rank glyph and nickname use game colors while explicit links keep their style" {
        val renderer = renderer { CompletableFuture.completedFuture(ExternalChatMeta("&6&r")) }
        val url = "https://example.com"
        val body = Component.text("hello").append(Component.text("link", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)))
        val runs = runs(renderer.render(playerId, "GrocerMC", body, ::line).join())
        runs.single { it.first == "" }.second.color() shouldBe NamedTextColor.WHITE
        runs.single { it.first == "GrocerMC" }.second.color()?.asHexString() shouldBe "#72b8e6"
        // ARC ChatMessageColorizer: global base #CFE7FF, FNV-1a nickname hue, amplitude 30 degrees.
        runs.single { it.first == "hello" }.second.color()?.asHexString() shouldBe "#cfe1ff"
        runs.single { it.first == "link" }.second.color() shouldBe NamedTextColor.AQUA
        runs.single { it.first == "link" }.second.clickEvent() shouldBe ClickEvent.openUrl(url)
    }

    "failed and synchronously unavailable metadata still deliver the message" {
        listOf<(UUID) -> CompletableFuture<ExternalChatMeta>>(
            { CompletableFuture.failedFuture(IllegalStateException("unavailable")) },
            { throw IllegalStateException("unavailable") },
        ).forEach { provider ->
            TextUtils.plain(renderer(provider).render(playerId, "GrocerMC", Component.text("hello"), ::line).join()) shouldBe
                "GrocerMC » hello"
        }
    }

    "native legacy colors reset decorations and support section and hex codes" {
        listOf("&lX&aY", "§lX§aY", "&lX&#55ff55Y", "&lX&x&5&5&f&f&5&5Y").forEach { prefix ->
            val renderer = renderer { CompletableFuture.completedFuture(ExternalChatMeta(prefix)) }
            val runs = runs(renderer.render(playerId, "GrocerMC", Component.text("hello"), ::line).join())
            runs.single { it.first == "X" }.second.decoration(TextDecoration.BOLD) shouldBe TextDecoration.State.TRUE
            runs.single { it.first == "Y" }.second.decoration(TextDecoration.BOLD) shouldNotBe TextDecoration.State.TRUE
            runs.single { it.first == "Y" }.second.color() shouldBe NamedTextColor.GREEN
        }
    }

    "nickname tint is case-insensitive and differs between speakers" {
        val renderer = renderer { CompletableFuture.completedFuture(ExternalChatMeta()) }
        fun color(name: String) = renderer.render(null, name, Component.text("body")) { _, body -> body }.join().color()
        color("GrocerMC") shouldBe color("gRoCeRmC")
        color("GrocerMC") shouldNotBe color("Alexey23")
    }

    "only the trusted prefix parses MiniMessage" {
        val renderer = renderer { CompletableFuture.completedFuture(ExternalChatMeta("<gold>[VIP]</gold>")) }
        val literal = "<click:run_command:'/op me'>hello</click>"
        val result = renderer.render(playerId, "<red>Guest", Component.text(literal), ::line).join()
        TextUtils.plain(result) shouldBe "[VIP] <red>Guest » $literal"
        runs(result).all { it.second.clickEvent() == null } shouldBe true
    }
})

private fun renderer(provider: (UUID) -> CompletableFuture<ExternalChatMeta>) =
    ExternalChatRenderer(metaProvider = provider, configProvider = { EmptyConfig })

private fun line(sender: Component, body: Component) =
    Component.empty().append(sender).append(Component.text(" » ")).append(body)

private fun runs(component: Component, inherited: Style = Style.empty()): List<Pair<String, Style>> {
    val style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
    return buildList {
        if (component is TextComponent && component.content().isNotEmpty()) add(component.content() to style)
        component.children().forEach { addAll(runs(it, style)) }
    }
}
