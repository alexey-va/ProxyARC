package ru.arc.xserver.repos

import com.google.gson.Gson
import org.apache.logging.log4j.LogManager
import ru.arc.Common
import ru.arc.xserver.RedisManager
import java.nio.file.Path
import java.util.Arrays
import java.util.Deque
import java.util.HashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Supplier

class RedisRepo<T : RepoData<T>> private constructor(
    loadAll: Boolean?,
    redisManager: RedisManager,
    storageKey: String,
    updateChannel: String,
    clazz: Class<T>,
    onUpdate: Consumer<T>?,
    id: String?,
    backupFolder: Path?,
    saveInterval: Long?,
    saveBackups: Boolean?,
) {
    @JvmField
    val map: ConcurrentHashMap<String, T> = ConcurrentHashMap()

    private val lastAttempt: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val messager: RedisRepoMessager
    private val gson: Gson = Common.gson
    private val contextSet: ConcurrentSkipListSet<String> = ConcurrentSkipListSet()
    private var saveTask: ProxyScheduler? = null
    private var lastFullRefresh: Long = 0

    @JvmField
    var onUpdate: Consumer<T>? = onUpdate

    private var backupService: BackupService? = null
    private val clazz: Class<T> = clazz
    private val storageKey: String = storageKey
    private val updateChannel: String = updateChannel
    private val redisManager: RedisManager = redisManager
    private val loadAll: Boolean = loadAll == true
    private val saveInterval: Long = saveInterval ?: 20L
    private val saveBackups: Boolean = saveBackups == true

    init {
        messager = RedisRepoMessager(this, redisManager)
        redisManager.registerChannelUnique(updateChannel, messager)
        redisManager.init()

        startTasks()
        log.info("Created repo: {}", id)

        repos.add(this)
    }

    fun cancelTasks() {
        if (saveTask != null && !saveTask!!.isCancelled()) {
            saveTask?.cancel()
        }
    }

    fun close() {
        cancelTasks()
        saveDirty().join()
        deleteUnnecessary().join()
        backupService?.saveBackup(map)
        redisManager.unregisterChannel(updateChannel, messager)
    }

    fun startTasks() {
        cancelTasks()
        saveTask = object : ProxyScheduler() {
            override fun run() {
                try {
                    saveDirty()
                    deleteUnnecessary()
                    loadNecessary()
                } catch (e: Exception) {
                    log.error("Error in save task: {}", e.message)
                }
            }
        }.runTaskTimer(0L, saveInterval)
    }

    fun addContext(context: String) {
        contextSet.add(context)
    }

    fun removeContext(context: String) {
        contextSet.remove(context)
    }

    fun all(): Collection<T> = map.values

    private fun loadAllFromStorage() {
        log.trace("Loading all")
        redisManager.loadMap(storageKey)
            .thenAccept { redisMap ->
                for ((_, value) in redisMap) {
                    try {
                        log.trace("Loading: {}", value)
                        val entity: T = gson.fromJson(value, clazz)
                        entity.dirty = false
                        map[entity.id()] = entity
                        contextSet.add(entity.id())
                        onUpdate?.accept(entity)
                    } catch (e: Exception) {
                        log.error("Error: {}", e.message)
                    }
                }
            }
    }

    private fun load(keys: List<String>): CompletableFuture<Void> {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }
        log.trace("Loading all: {}", keys)
        return redisManager.loadMapEntries(storageKey, *keys.toTypedArray())
            .thenAccept { list ->
                for (i in list.indices) {
                    val entry = list[i]
                    if (entry == null) {
                        log.debug("Could not find entry in storage: {}", keys)
                        lastAttempt[keys[i]] = System.currentTimeMillis()
                        continue
                    }
                    try {
                        val entity: T = gson.fromJson(entry, clazz)
                        entity.dirty = false
                        map[entity.id()] = entity
                        contextSet.add(entity.id())
                        lastAttempt.remove(entity.id())
                        onUpdate?.accept(entity)
                    } catch (e: Exception) {
                        log.error("Could not parse: {} {}", entry, e.message)
                    }
                }
            }
    }

    private fun loadNecessary(): CompletableFuture<Void> {
        if (loadAll) {
            if (System.currentTimeMillis() - lastFullRefresh < 1000 * 60 * 5) {
                return CompletableFuture.completedFuture(null)
            }
            log.trace("Loading all (full refresh)")
            return saveDirty().thenAccept {
                lastFullRefresh = System.currentTimeMillis()
                loadAllFromStorage()
            }
        }
        return loadContext()
    }

    private fun loadContext(): CompletableFuture<Void> {
        val uniqueToLoaded = HashSet(map.keys)
        val uniqueToContext = HashSet(contextSet)

        uniqueToLoaded.removeAll(contextSet)
        uniqueToContext.removeAll(map.keys)

        deleteEntries(uniqueToLoaded)

        return load(
            uniqueToContext
                .filter { System.currentTimeMillis() - lastAttempt.getOrDefault(it, 0L) > 1000 * 60 }
                .toList(),
        )
    }

    private fun saveDirty(): CompletableFuture<Void> {
        val toSave = map.values.filter { it.dirty }
        log.trace("Saving dirty: {}", toSave)
        return saveInStorage(toSave)
    }

    private fun deleteUnnecessary(): CompletableFuture<Void> {
        val toDelete = map.values.filter { it.isRemove() }
        if (toDelete.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }
        log.trace("Deleting unnecessary: {}", toDelete)
        val futures = toDelete.map { delete(it) }.toTypedArray()
        return CompletableFuture.allOf(*futures)
    }

    private fun deleteInStorage(ts: Collection<T>): CompletableFuture<Void> {
        if (ts.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }
        log.trace("Deleting in storage: {}", ts)
        return CompletableFuture.supplyAsync {
            ts.flatMap { listOf<String?>(it.id(), null) }.toTypedArray()
        }.thenCompose { arr ->
            redisManager.saveMapEntries(storageKey, *arr)
        }.thenAccept {
            ts.forEach { announceDelete(it.id()) }
        }
    }

    private fun saveInStorage(ts: Collection<T>): CompletableFuture<Void> {
        return try {
            if (ts.isEmpty()) {
                CompletableFuture.completedFuture(null)
            } else {
                log.trace("Saving in storage: {}", ts)
                ts.forEach { it.dirty = false }
                CompletableFuture.supplyAsync {
                    try {
                        val array = ts.flatMap { listOf(it.id(), gson.toJson(it)) }.toTypedArray()
                        log.trace("Saving: {}", Arrays.toString(array))
                        array
                    } catch (e: Exception) {
                        log.error("Could not save: {}", ts)
                        emptyArray()
                    }
                }.thenCompose { arr ->
                    redisManager.saveMapEntries(storageKey, *arr)
                }.thenAccept {
                    ts.forEach { announceUpdate(it.id()) }
                }
            }
        } catch (e: Exception) {
            log.error("Error: {}", e.message)
            CompletableFuture.completedFuture(null)
        }
    }

    fun forceSave() {
        saveDirty()
    }

    private fun announceUpdate(id: String) {
        log.trace("Announcing update: {}", id)
        val entity = map[id]
        if (entity == null) {
            log.debug("Could not find {} in storage while announcing update!", id)
            return
        }
        val update = Update(id, 0)
        redisManager.publish(updateChannel, gson.toJson(update))
    }

    private fun announceDelete(id: String) {
        log.trace("Announcing delete: {}", id)
        val update = Update(id, 0)
        redisManager.publish(updateChannel, gson.toJson(update))
    }

    fun receiveUpdate(message: String) {
        val update = gson.fromJson(message, Update::class.java)
        if (!loadAll && !contextSet.contains(update.id)) {
            log.trace("Not in context: {}", update.id)
            return
        }
        redisManager.loadMapEntries(storageKey, update.id)
            .thenAccept { list ->
                if (list.isEmpty() || list.first() == null) {
                    deleteEntry(update.id)
                    return@thenAccept
                }
                val entity: T = gson.fromJson(list.first(), clazz)
                val current = map[update.id]
                if (current != null) {
                    current.merge(entity)
                } else {
                    log.debug("Current is null when merging! Update {}", entity)
                    map[entity.id()] = entity
                    contextSet.add(entity.id())
                }
                entity.dirty = false
                onUpdate?.accept(entity)
            }
    }

    private fun deleteEntries(ids: Collection<String>) {
        ids.forEach { deleteEntry(it) }
    }

    private fun deleteEntry(id: String) {
        map.remove(id)
    }

    fun getOrCreate(id: String, supplier: Supplier<T>): CompletableFuture<T> {
        map[id]?.let { return CompletableFuture.completedFuture(it) }
        return redisManager.loadMapEntries(storageKey, id)
            .thenApply { list ->
                log.trace("Received: {}", list)
                if (list.isEmpty() || list.first() == null) {
                    val entity = supplier.get()
                    log.trace("Creating: {}", entity)
                    create(entity).join()
                    log.trace("Created: {}", entity)
                    entity
                } else {
                    val entity: T = gson.fromJson(list.first(), clazz)
                    map[entity.id()] = entity
                    contextSet.add(entity.id())
                    entity
                }
            }
            .orTimeout(5, TimeUnit.SECONDS)
    }

    fun getOrNull(id: String): CompletableFuture<T?> {
        map[id]?.let { return CompletableFuture.completedFuture(it) }
        if (lastAttempt.getOrDefault(id, 0L) > System.currentTimeMillis() - 1000 * 60) {
            return CompletableFuture.completedFuture(null)
        }
        return redisManager.loadMapEntries(storageKey, id)
            .thenApply { list ->
                if (list.isEmpty() || list.first() == null) {
                    lastAttempt[id] = System.currentTimeMillis()
                    null
                } else {
                    val entity: T = gson.fromJson(list.first(), clazz)
                    map[entity.id()] = entity
                    contextSet.add(entity.id())
                    entity
                }
            }
            .orTimeout(5, TimeUnit.SECONDS)
    }

    fun getNow(string: String): T? = map[string]

    fun create(entity: T): CompletableFuture<Void> {
        map[entity.id()] = entity
        contextSet.add(entity.id())
        return saveInStorage(listOf(entity))
    }

    fun delete(entity: T): CompletableFuture<Void> {
        log.trace("Deleting entry: {}", entity.id())
        deleteEntry(entity.id())
        contextSet.remove(entity.id())
        return deleteInStorage(listOf(entity))
    }

    data class Update(
        var id: String = "",
        var l: Long = 0,
    )

    class RedisRepoBuilder<T : RepoData<T>> internal constructor(
        private var entityClass: Class<T>,
    ) {
        private var loadAll: Boolean? = null
        private var redisManager: RedisManager? = null
        private var storageKey: String? = null
        private var updateChannel: String? = null
        private var onUpdate: Consumer<T>? = null
        private var id: String? = null
        private var backupFolder: Path? = null
        private var saveInterval: Long? = null
        private var saveBackups: Boolean? = null

        fun loadAll(loadAll: Boolean?): RedisRepoBuilder<T> {
            this.loadAll = loadAll
            return this
        }

        fun redisManager(redisManager: RedisManager): RedisRepoBuilder<T> {
            this.redisManager = redisManager
            return this
        }

        fun storageKey(storageKey: String): RedisRepoBuilder<T> {
            this.storageKey = storageKey
            return this
        }

        fun updateChannel(updateChannel: String): RedisRepoBuilder<T> {
            this.updateChannel = updateChannel
            return this
        }

        fun clazz(clazz: Class<*>): RedisRepoBuilder<T> {
            @Suppress("UNCHECKED_CAST")
            entityClass = clazz as Class<T>
            return this
        }

        fun onUpdate(onUpdate: Consumer<T>): RedisRepoBuilder<T> {
            this.onUpdate = onUpdate
            return this
        }

        fun id(id: String): RedisRepoBuilder<T> {
            this.id = id
            return this
        }

        fun backupFolder(backupFolder: Path): RedisRepoBuilder<T> {
            this.backupFolder = backupFolder
            return this
        }

        fun saveInterval(saveInterval: Long): RedisRepoBuilder<T> {
            this.saveInterval = saveInterval
            return this
        }

        fun saveBackups(saveBackups: Boolean): RedisRepoBuilder<T> {
            this.saveBackups = saveBackups
            return this
        }

        fun build(): RedisRepo<T> = RedisRepo(
            loadAll,
            redisManager!!,
            storageKey!!,
            updateChannel!!,
            entityClass,
            onUpdate,
            id,
            backupFolder,
            saveInterval,
            saveBackups,
        )
    }

    companion object {
        private val log = LogManager.getLogger(RedisRepo::class.java)
        private val repos: Deque<RedisRepo<*>> = ConcurrentLinkedDeque()

        @JvmStatic
        fun saveAll() {
            for (repo in repos) {
                repo.forceSave()
            }
        }

        @JvmStatic
        fun <T : RepoData<T>> builder(clazz: Class<T>): RedisRepoBuilder<T> =
            RedisRepoBuilder(clazz)
    }
}
