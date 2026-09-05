package ru.arc.core.modules

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import ru.arc.Common
import ru.arc.chat.ChatModeService
import ru.arc.core.PluginModule
import ru.arc.join.JoinAnnouncementKind
import ru.arc.join.JoinMessageCatalog
import ru.arc.join.JoinMessageCatalogConfig
import ru.arc.join.JoinMessageCatalogPublication
import ru.arc.redis.RedisOperations
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import ru.arc.velocity.Velocity
import ru.arc.xserver.JoinMessages
import ru.arc.xserver.PlayerListAnnouncer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

// ==================== Priority 60-69: Cross-Server ====================

object PlayerListModule : PluginModule {
    override val name = "PlayerList"
    override val priority = 60

    override fun init() {
        val redis = Velocity.redisManager ?: return
        Velocity.playerListAnnouncer =
            PlayerListAnnouncer(
                redis,
                "arc.proxy_player_list",
            )
    }

    override fun shutdown() {
        Velocity.playerListAnnouncer = null
    }

    override fun reload() {}
}

object JoinMessageCatalogModule : PluginModule {
    override val name = "JoinMessageCatalog"
    override val priority = 64

    @Volatile
    private var repository: CachedRepository<JoinMessageCatalog>? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var dataRoot: Path? = null

    private var publication: JoinMessageCatalogPublication? = null

    override fun init() {
        val redis = Velocity.redisManager ?: return
        start(redis, Velocity.requireDataFolder())
    }

    @Synchronized
    internal fun start(
        redis: RedisOperations,
        root: Path,
    ) {
        if (repository != null) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var startedRepository: CachedRepository<JoinMessageCatalog>? = null
        try {
            val newRepository =
                redisRepo<JoinMessageCatalog>(
                    redis = redis,
                    gson = Common.gson,
                    id = "join_message_catalog",
                    storageKey = STORAGE_KEY,
                    updateChannel = UPDATE_CHANNEL,
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            startedRepository = newRepository
            val newPublication =
                JoinMessageCatalogPublication(
                    current = { newRepository.getNow(JoinMessageCatalog.CATALOG_ID) },
                    persist = { snapshot ->
                        newRepository.save(snapshot).getOrThrow()
                        newRepository.saveDirty().getOrThrow()
                    },
                )
            repository = newRepository
            scope = newScope
            dataRoot = root
            publication = newPublication
            runBlocking {
                newPublication.publish(JoinMessageCatalogConfig.load(root).snapshot())
            }
        } catch (e: Exception) {
            repository = null
            scope = null
            dataRoot = null
            publication = null
            runBlocking { startedRepository?.shutdown() }
            newScope.cancel()
            throw e
        }
    }

    internal fun selectedMessage(
        messages: JoinMessages?,
        kind: JoinAnnouncementKind,
    ): String? {
        val catalog = repository?.getNow(JoinMessageCatalog.CATALOG_ID) ?: return null
        val selected =
            when (kind) {
                JoinAnnouncementKind.FIRST_TIME -> emptySet()
                JoinAnnouncementKind.JOIN -> messages?.joinMessages.orEmpty()
                JoinAnnouncementKind.LEAVE -> messages?.leaveMessages.orEmpty()
            }
        val custom =
            when (kind) {
                JoinAnnouncementKind.FIRST_TIME -> emptySet()
                JoinAnnouncementKind.JOIN -> messages?.customJoinMessages.orEmpty()
                JoinAnnouncementKind.LEAVE -> messages?.customLeaveMessages.orEmpty()
            }
        return catalog.randomSelectedMessage(kind, selected, custom)
    }

    @Synchronized
    override fun reload() {
        val root = dataRoot ?: return
        val currentPublication = publication ?: return
        runBlocking {
            currentPublication.publish(JoinMessageCatalogConfig.load(root).snapshot())
        }
    }

    @Synchronized
    override fun shutdown() {
        val currentRepository = repository
        repository = null
        scope = null
        dataRoot = null
        publication = null
        if (currentRepository != null) {
            runBlocking { currentRepository.shutdown() }
        }
    }

    internal const val STORAGE_KEY = "arc.join_message_catalog"
    internal const val UPDATE_CHANNEL = "arc.join_message_catalog_update"
}

object JoinMessagesModule : PluginModule {
    override val name = "JoinMessages"
    override val priority = 65

    @Volatile
    private var repository: CachedRepository<JoinMessages>? = null

    @Volatile
    private var scope: CoroutineScope? = null

    override fun init() {
        val redis = Velocity.redisManager ?: return
        start(redis)
    }

    @Synchronized
    internal fun start(redis: RedisOperations) {
        if (repository != null) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            repository =
                redisRepo<JoinMessages>(
                    redis = redis,
                    gson = Common.gson,
                    id = "join_messages",
                    storageKey = "arc.join_messages",
                    updateChannel = "arc.join_messages_update",
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    saveInterval(10.seconds)
                }
            scope = newScope
        } catch (e: Exception) {
            newScope.cancel()
            throw e
        }
    }

    internal fun loadAsync(playerName: String): CompletableFuture<JoinMessages?> {
        val currentRepository = repository ?: return CompletableFuture.completedFuture(null)
        val currentScope = scope ?: return CompletableFuture.completedFuture(null)
        return currentScope.future {
            currentRepository.get(playerName).getOrThrow()
        }
    }

    @Synchronized
    override fun shutdown() {
        val currentRepository = repository
        repository = null
        scope = null
        if (currentRepository != null) {
            runBlocking { currentRepository.shutdown() }
        }
    }

    override fun reload() {}
}

object ChatModeModule : PluginModule {
    override val name = "ChatMode"
    override val priority = 66

    override fun init() {
        val redis = Velocity.redisManager ?: return
        ChatModeService.start(redis)
    }

    override fun shutdown() {
        ChatModeService.shutdown()
    }

    override fun reload() {}
}
