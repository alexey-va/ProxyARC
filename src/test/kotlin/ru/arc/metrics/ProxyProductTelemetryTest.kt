package ru.arc.metrics

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import ru.arc.product.ProductConnection
import ru.arc.product.ProductDetailType
import ru.arc.product.ProductEventKind
import ru.arc.product.ProductFeature
import ru.arc.product.ProductWireCodec
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import java.util.UUID

class ProxyProductTelemetryTest :
    FreeSpec({
        "publishes only a sanitized command root plus bounded feature signals" {
            val now = 1_800_000_000_000L
            val redis = InMemoryRedis()
            val telemetry =
                ProxyProductTelemetry(
                    registry = SimpleMeterRegistry(),
                    config = ProxyProductConfig(),
                    redis = redis,
                    rawSource = "proxy",
                    gson = Gson(),
                    clockMillis = { now },
                )

            telemetry.command(UUID.fromString("00000000-0000-0000-0000-000000000123"), "Player", "rtp secret-world 12 64 -9")

            val payloads = redis.getPublishedMessages().map { it.message }
            payloads.size shouldBe 3
            payloads.any { "secret-world" in it || "12 64" in it } shouldBe false
            val decoded = payloads.mapNotNull { ProductWireCodec.decode(it, "proxy", now, 35, Gson()) }
            decoded.map { it.kind } shouldContainExactly
                listOf(ProductEventKind.DETAIL, ProductEventKind.PATH_INTEREST, ProductEventKind.FEATURE_INTEREST)
            decoded.first().detail?.type shouldBe ProductDetailType.COMMAND
            decoded.first().detail?.key shouldBe "rtp"
            decoded.last().feature shouldBe ProductFeature.RTP
        }

        "keeps QA commands out of the product event bus" {
            val registry = SimpleMeterRegistry()
            val redis = InMemoryRedis()
            val telemetry = ProxyProductTelemetry(registry, ProxyProductConfig(), redis, "proxy")

            telemetry.command(UUID.randomUUID(), "CodexQA_728", "rtp")

            redis.getPublishedMessages().size shouldBe 0
            registry.find("arc_product_proxy_events").tags("event", "command", "cohort", "qa").counter()!!.count() shouldBeExactly 1.0
            registry.find("arc_product_proxy_events").tags("event", "command", "cohort", "organic").counter()!!.count() shouldBeExactly 0.0
        }

        "records exact successful backends separately from failed targets" {
            val now = 1_800_000_000_000L
            val registry = SimpleMeterRegistry()
            val redis = InMemoryRedis()
            val telemetry = ProxyProductTelemetry(registry, ProxyProductConfig(), redis, "proxy", clockMillis = { now })
            val player = UUID.randomUUID()

            telemetry.serverConnected(player, "Player", "classic", "classic_survival")
            telemetry.connectDenied(player, "Player", "parkour")
            telemetry.disconnect(player, "Player", ProductConnection.DISCONNECT_ACTIVE)

            val details =
                redis.getPublishedMessages()
                    .mapNotNull { ProductWireCodec.decode(it.message, "proxy", now, 35, Gson())?.detail }
            details.map { it.type to it.key } shouldContainExactly
                listOf(
                    ProductDetailType.SERVER to "classic_survival",
                    ProductDetailType.CONNECTION to "server_switch",
                    ProductDetailType.SERVER_TARGET to "parkour",
                    ProductDetailType.CONNECTION to "connect_denied",
                    ProductDetailType.CONNECTION to "disconnect_active",
                )
            registry.find("arc_product_proxy_server_transitions").tags("from", "spawn", "to", "survival").counter()!!.count() shouldBeExactly 1.0
        }

        "contains Redis publication failures without breaking gameplay events" {
            val registry = SimpleMeterRegistry()
            val redis = mockk<RedisOperations>(relaxed = true)
            every { redis.publish(any(), any()) } throws IllegalStateException("offline")
            val telemetry = ProxyProductTelemetry(registry, ProxyProductConfig(), redis, "proxy")

            telemetry.command(UUID.randomUUID(), "Player", "rtp")

            registry.find("arc_product_proxy_transport").tag("result", "publish_failed").counter()!!.count() shouldBeExactly 3.0
        }
    })
