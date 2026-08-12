package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.resourcepack.ResourcePackPublication
import java.util.concurrent.CompletableFuture

class ResourcePackHashRefreshListenerTest :
    FreeSpec({
        "resource-pack hash refresh listener" - {
            "executes the fixed command and acknowledges a trusted publication" {
                var executions = 0
                val acknowledgements = mutableListOf<Pair<String, ResourcePackPublication.Request>>()
                val listener =
                    ResourcePackHashRefreshListener(
                        commandAvailable = { true },
                        executeGenerateHashes = {
                            executions++
                            CompletableFuture.completedFuture(true)
                        },
                        acknowledge = { origin, request ->
                            acknowledgements += origin to request
                            CompletableFuture.completedFuture(null)
                        },
                    )
                val request = request("ab", "cd")

                listener.consume(ResourcePackPublication.CHANNEL, encoded(request), "spawn")

                executions shouldBe 1
                acknowledgements.shouldContainExactly("spawn" to request)
            }

            "rejects malformed, wrong-channel, and untrusted publications" {
                var executions = 0
                val listener =
                    ResourcePackHashRefreshListener(
                        commandAvailable = { true },
                        executeGenerateHashes = {
                            executions++
                            CompletableFuture.completedFuture(true)
                        },
                        acknowledge = { _, _ -> CompletableFuture.completedFuture(null) },
                    )
                val request = request("ab", "cd")

                listener.consume("other.channel", encoded(request), "spawn")
                listener.consume(ResourcePackPublication.CHANNEL, "command:shutdown", "spawn")
                listener.consume(ResourcePackPublication.CHANNEL, encoded(request), "attacker")

                executions shouldBe 0
            }

            "coalesces the same hash and acknowledges every request after success" {
                val commandResult = CompletableFuture<Boolean>()
                var executions = 0
                val acknowledgements = mutableListOf<Pair<String, ResourcePackPublication.Request>>()
                val listener =
                    ResourcePackHashRefreshListener(
                        commandAvailable = { true },
                        executeGenerateHashes = {
                            executions++
                            commandResult
                        },
                        acknowledge = { origin, request ->
                            acknowledgements += origin to request
                            CompletableFuture.completedFuture(null)
                        },
                    )
                val first = request("ab", "cd")
                val second = request("ab", "ef")

                listener.consume(ResourcePackPublication.CHANNEL, encoded(first), "spawn")
                listener.consume(ResourcePackPublication.CHANNEL, encoded(second), "survival")
                executions shouldBe 1
                acknowledgements shouldBe emptyList()

                commandResult.complete(true)

                executions shouldBe 1
                acknowledgements.shouldContainExactly("spawn" to first, "survival" to second)
            }

            "does not acknowledge failure and allows the same hash to retry" {
                var executions = 0
                val acknowledgements = mutableListOf<Pair<String, ResourcePackPublication.Request>>()
                val listener =
                    ResourcePackHashRefreshListener(
                        commandAvailable = { true },
                        executeGenerateHashes = {
                            executions++
                            CompletableFuture.completedFuture(executions > 1)
                        },
                        acknowledge = { origin, request ->
                            acknowledgements += origin to request
                            CompletableFuture.completedFuture(null)
                        },
                    )
                val first = request("ab", "cd")
                val retry = request("ab", "ef")

                listener.consume(ResourcePackPublication.CHANNEL, encoded(first), "spawn")
                listener.consume(ResourcePackPublication.CHANNEL, encoded(retry), "spawn")

                executions shouldBe 2
                acknowledgements.shouldContainExactly("spawn" to retry)
            }

            "bounds queued hashes while a refresh is running" {
                val firstResult = CompletableFuture<Boolean>()
                var executions = 0
                val warnings = mutableListOf<String>()
                val acknowledgements = mutableListOf<ResourcePackPublication.Request>()
                val listener =
                    ResourcePackHashRefreshListener(
                        commandAvailable = { true },
                        executeGenerateHashes = {
                            executions++
                            if (executions == 1) firstResult else CompletableFuture.completedFuture(true)
                        },
                        acknowledge = { _, request ->
                            acknowledgements += request
                            CompletableFuture.completedFuture(null)
                        },
                        warn = warnings::add,
                    )

                val requests = (1..10).map { index -> request("%02x".format(index), "cd") }
                requests.forEach { publication ->
                    listener.consume(ResourcePackPublication.CHANNEL, encoded(publication), "spawn")
                }
                firstResult.complete(true)

                executions shouldBe 9
                acknowledgements.size shouldBe 9
                warnings.size shouldBe 1
            }
        }
    })

private fun request(
    hashByte: String,
    requestByte: String,
) = ResourcePackPublication.Request(hashByte.repeat(32), requestByte.repeat(16))

private fun encoded(request: ResourcePackPublication.Request): String =
    ResourcePackPublication.encode(request.sha256, request.requestId)
