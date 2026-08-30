package ru.ruscrafting.votes.velocity

import ru.arc.core.PluginModule
import ru.arc.sql.SqlRuntime
import ru.arc.velocity.Velocity
import ru.ruscrafting.votes.callback.ArcVoteHttpServer
import ru.ruscrafting.votes.callback.VoteIngressService
import ru.ruscrafting.votes.config.ArcVotesSettings
import ru.ruscrafting.votes.storage.MySqlVoteRepository
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Authenticated vote ingress hosted by ProxyARC on its own loopback-only port. */
object ProxyVotesModule : PluginModule {
    override val name = "ProxyVotes"
    override val priority = 85

    private var sqlRuntime: SqlRuntime? = null
    private var httpServer: ArcVoteHttpServer? = null
    private var ingress: VoteIngressService? = null

    override fun init() {
        val settings = ArcVotesSettings.load(checkNotNull(Velocity.dataFolder) { "ProxyARC data folder is not initialized" })
        if (!settings.http.enabled) {
            checkNotNull(Velocity.logger).info("ProxyVotes callback ingress is disabled")
            return
        }

        val sqlConfig = requireNotNull(settings.sql) { "ProxyVotes requires MySQL when callback ingress is enabled" }
        val runtime = SqlRuntime.create(sqlConfig, "proxy-votes")
        try {
            val repository = MySqlVoteRepository(runtime)
            repository.initialize().get(INIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            val logger = checkNotNull(Velocity.logger)
            val service = VoteIngressService(settings, repository, logger)
            val server = ArcVoteHttpServer(settings.http, service, logger)
            server.start()

            sqlRuntime = runtime
            ingress = service
            httpServer = server
            checkNotNull(Velocity.logger).info(
                "ProxyVotes callback ingress listening on {}:{} with {} enabled sources",
                settings.http.bindAddress.hostAddress,
                settings.http.port,
                settings.enabledSources.size,
            )
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }

    override fun shutdown() {
        httpServer?.close()
        httpServer = null
        ingress = null
        sqlRuntime?.close()
        sqlRuntime = null
    }

    override fun reload() {
        shutdown()
        init()
    }

    internal fun snapshot() = ingress?.snapshot()

    private val INIT_TIMEOUT = Duration.ofSeconds(20)
}
