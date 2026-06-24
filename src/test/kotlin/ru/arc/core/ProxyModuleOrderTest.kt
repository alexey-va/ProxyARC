package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.core.ModuleRegistry.resetForTests

class ProxyModuleOrderTest : FreeSpec({
    beforeTest { resetForTests() }

    "Proxy module registry" - {
        "should init and shutdown in priority order" {
            val order = mutableListOf<String>()
            val early =
                object : PluginModule {
                    override val name = "Early"
                    override val priority = 10
                    override fun init() {
                        order.add("init-early")
                    }

                    override fun shutdown() {
                        order.add("shutdown-early")
                    }
                }
            val late =
                object : PluginModule {
                    override val name = "Late"
                    override val priority = 90
                    override fun init() {
                        order.add("init-late")
                    }

                    override fun shutdown() {
                        order.add("shutdown-late")
                    }
                }

            ModuleRegistry.registerAll(late, early)
            ModuleRegistry.initAll()
            order.take(2) shouldContainExactly listOf("init-early", "init-late")

            ModuleRegistry.shutdownAll()
            order.drop(2) shouldContainExactly listOf("shutdown-late", "shutdown-early")
        }

        "should skip disabled modules" {
            var called = false
            ModuleRegistry.register(
                object : PluginModule {
                    override val name = "Off"
                    override val enabled = false
                    override fun init() {
                        called = true
                    }

                    override fun shutdown() {}
                },
            )
            ModuleRegistry.initAll()
            called shouldBe false
            ModuleRegistry.shutdownAll()
        }
    }
})
