package ru.arc.discord

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.nio.file.Path

/** Configuration for player-facing Discord integration beyond the chat bridge. */
internal data class DiscordIntegrationConfig(
    val enabled: Boolean,
    val guildId: String,
    val statusChannelId: String?,
    val alertsChannelId: String?,
    val announcementsChannelId: String?,
    val participantRoleId: String?,
    val winnerRoleId: String?,
    val presenceEnabled: Boolean,
    val presenceFormat: String,
    val refreshSeconds: Long,
    val notificationRatePerMinute: Int,
    val eventReminderMinutes: List<Long>,
    val recoveryRequestTtlSeconds: Long,
    val linkProtectionDelaySeconds: Long,
    val messages: DiscordIntegrationMessages,
) {
    fun validate() {
        if (!enabled) return
        require(DiscordVerificationConfig.validSnowflake(guildId)) { "integration guild-id must be a Discord snowflake" }
        listOfNotNull(
            statusChannelId,
            alertsChannelId,
            announcementsChannelId,
            participantRoleId,
            winnerRoleId,
        ).forEach { require(DiscordVerificationConfig.validSnowflake(it)) { "integration Discord ids must be snowflakes" } }
        require(refreshSeconds in 30..3_600) { "presence refresh-seconds must be between 30 and 3600" }
        require(notificationRatePerMinute in 1..30) { "notifications max-per-minute must be between 1 and 30" }
        require(eventReminderMinutes.size <= 6 && eventReminderMinutes.all { it in 1..10_080 }) {
            "event reminder-minutes must contain at most six values between 1 and 10080"
        }
        require(recoveryRequestTtlSeconds in 300..86_400) {
            "recovery request-ttl-seconds must be between 300 and 86400"
        }
        require(linkProtectionDelaySeconds in 0..90) {
            "recovery link-protection-delay-seconds must be between 0 and 90"
        }
        messages.validate()
    }

    companion object {
        private const val FILE_NAME = "discord-integration.yml"

        fun load(): DiscordIntegrationConfig = load(ProxyConfigs.dataRoot())

        fun load(dataRoot: Path): DiscordIntegrationConfig {
            val config = ProxyConfigs.module(dataRoot, FILE_NAME)
            fun snowflakeOrNull(path: String): String? =
                config.string(path, "none").trim().takeUnless { it.isEmpty() || it.equals("none", true) }

            return DiscordIntegrationConfig(
                enabled = config.bool("enabled", false),
                guildId = config.string("guild-id", "none").trim(),
                statusChannelId = snowflakeOrNull("channels.status"),
                alertsChannelId = snowflakeOrNull("channels.alerts"),
                announcementsChannelId = snowflakeOrNull("channels.announcements"),
                participantRoleId = snowflakeOrNull("events.participant-role-id"),
                winnerRoleId = snowflakeOrNull("events.winner-role-id"),
                presenceEnabled = config.bool("presence.enabled", true),
                presenceFormat = config.string("presence.format", "%online% игроков • play.rus-crafting.ru"),
                refreshSeconds = config.long("presence.refresh-seconds", 60),
                notificationRatePerMinute = config.integer("notifications.max-per-minute", 8),
                eventReminderMinutes =
                    config.stringList("events.reminder-minutes", listOf("60", "15"))
                        .map { value ->
                            value.toLongOrNull()
                                ?: throw IllegalArgumentException("events.reminder-minutes must contain integers")
                        }
                        .distinct()
                        .sortedDescending(),
                recoveryRequestTtlSeconds = config.long("recovery.request-ttl-seconds", 1_800),
                linkProtectionDelaySeconds = config.long("recovery.link-protection-delay-seconds", 60),
                messages = DiscordIntegrationMessages(config),
            )
        }
    }
}

internal class DiscordIntegrationMessages(private val config: Config) {
    fun text(path: String, vararg placeholders: Pair<String, String>): String {
        var value = config.string("messages.$path", DEFAULTS[path] ?: path)
        placeholders.forEach { (key, replacement) -> value = value.replace("%$key%", replacement) }
        return value.take(2_000)
    }

    fun validate() {
        COMMAND_DESCRIPTIONS.forEach { key ->
            val value = text("commands.$key")
            require(value.length in 1..100) { "Discord command description messages.commands.$key must be 1..100 chars" }
        }
        BUTTON_LABELS.forEach { key ->
            val value = text(key)
            require(value.length in 1..80) { "Discord button label messages.$key must be 1..80 chars" }
        }
    }

    companion object {
        private val COMMAND_DESCRIPTIONS =
            setOf(
                "account",
                "online",
                "server",
                "server-option",
                "player",
                "player-option",
                "notifications",
                "invite",
                "invite-player",
                "event",
                "event-action",
                "event-name",
                "event-description",
                "event-start",
                "event-winner",
            )
        private val BUTTON_LABELS =
            setOf(
                "account-sync",
                "account-unlink",
                "account-recovery",
                "event-join",
                "event-leave",
                "security-link-cancel",
            )

        private val DEFAULTS =
            mapOf(
                "commands.account" to "Показать и синхронизировать игровой аккаунт",
                "commands.online" to "Показать игроков онлайн",
                "commands.server" to "Показать состояние игрового сервера",
                "commands.server-option" to "Сервер: spawn, survival, parkour, end или etd",
                "commands.player" to "Найти игрока RusCrafting",
                "commands.player-option" to "Игровой ник",
                "commands.notifications" to "Настроить личные уведомления",
                "commands.invite" to "Пригласить игрока на свой сервер",
                "commands.invite-player" to "Игровой ник получателя",
                "commands.event" to "Управлять игровым событием",
                "commands.event-action" to "Действие: create, finish или cancel",
                "commands.event-name" to "Название события",
                "commands.event-description" to "Короткое описание",
                "commands.event-start" to "Начало: ГГГГ-ММ-ДД ЧЧ:ММ (MSK)",
                "commands.event-winner" to "Игровой ник победителя",
                "not-linked" to "Сначала свяжите аккаунт командой `/verify` на игровом сервере.",
                "account-title" to "Аккаунт RusCrafting",
                "account-body" to "Minecraft: **%player%**\nСервер: **%server%**\nПривязан: <t:%linked_at%:D>\nРоли: **%roles%**\nСинхронизация: **%sync%**",
                "account-sync" to "Синхронизировать",
                "account-unlink" to "Отвязать",
                "account-recovery" to "Не могу войти",
                "account-synced" to "Роли и игровой ник синхронизированы.",
                "account-sync-failed" to "Синхронизация не выполнена: %reason%.",
                "account-unlink-confirm" to "Нажмите ещё раз, чтобы отвязать аккаунт и снять управляемые роли.",
                "account-unlinked" to "Аккаунт отвязан, управляемые роли сняты.",
                "account-operation-failed" to "Операция не выполнена. Данные аккаунта сохранены.",
                "online-title" to "Сейчас на RusCrafting",
                "online-empty" to "Сейчас на серверах никого нет.",
                "server-not-found" to "Сервер `%server%` не найден.",
                "player-not-found" to "Игрок `%player%` не найден.",
                "player-body" to "Игрок: **%player%**\nСостояние: **%status%**\nСервер: **%server%**\nDiscord: **%verified%**",
                "label-online" to "в сети",
                "label-offline" to "не в сети",
                "label-connecting" to "подключение",
                "label-none" to "нет",
                "label-nobody" to "никого",
                "label-not-specified" to "не указан",
                "label-verified" to "подтверждён",
                "label-unverified" to "не подтверждён",
                "sync-status.never" to "ещё не запускалась",
                "sync-status.updated" to "обновлено",
                "sync-status.unchanged" to "актуально",
                "sync-status.not_ready" to "Discord не готов",
                "sync-status.member_not_found" to "участник не найден",
                "sync-status.config_error" to "ошибка настройки",
                "sync-status.hierarchy_blocked" to "мешает иерархия ролей",
                "sync-status.provider_unavailable" to "LuckPerms недоступен",
                "sync-status.failed" to "ошибка",
                "online-server-block" to "**%server% • %count%**\n%players%",
                "server-list-item" to "**%server%** — %count% онлайн",
                "server-body" to "## %server%\nОнлайн: **%count%**\n%players%",
                "notifications-title" to "Личные уведомления",
                "notifications-body" to "Нажмите кнопку, чтобы включить или выключить уведомление. Все категории изначально выключены.",
                "notification-mentions" to "Упоминания",
                "notification-auction" to "Продажи",
                "notification-tickets" to "Ответы по багам",
                "notification-punishments" to "Наказания",
                "notification-events" to "События",
                "notification-invites" to "Приглашения",
                "notification-enabled" to "вкл.",
                "notification-disabled" to "выкл.",
                "invite-body" to "Игрок **%sender%** приглашает вас на сервер **%server%**.",
                "invite-sent" to "Приглашение доставлено.",
                "invite-unavailable" to "Приглашение не доставлено: игрок не привязан, отключил этот тип уведомлений или недоступен в Discord.",
                "invite-sender-offline" to "Чтобы приглашать игроков, сначала зайдите на игровой сервер.",
                "recovery-created" to "Заявка **%request%** создана на %minutes% мин. Она не сбрасывает пароль автоматически: администрация проверит её и выполнит защищённый сброс LimboAuth.",
                "recovery-active" to "У вас уже есть активная заявка **%request%**.",
                "recovery-alert" to "Запрос восстановления LimboAuth\nИгрок: **%player%**\nDiscord: <@%discord%>\nЗаявка: `%request%`\nИстекает: <t:%expires_at%:R>",
                "event-permission" to "Для управления событиями нужно право «Управлять событиями».",
                "event-action-help" to "Действие: `create`, `finish` или `cancel`.",
                "event-create-help" to "Для `create` укажите `name`, `description` и `start` в формате `2026-08-26 20:00` (MSK).",
                "event-winner-help" to "Для `finish` укажите игровой ник в поле `winner`.",
                "event-created" to "Событие **%event%** опубликовано.",
                "event-none" to "Активного события нет.",
                "event-joined" to "Вы участвуете в событии **%event%**.",
                "event-left" to "Вы больше не участвуете в событии **%event%**.",
                "event-finished" to "Событие **%event%** завершено.",
                "event-finished-with-warnings" to "Событие **%event%** закрыто, но Discord не выполнил операций: **%failures%**. Проверьте роли вручную.",
                "event-cancelled" to "Событие **%event%** отменено.",
                "event-join" to "Участвовать",
                "event-leave" to "Не участвую",
                "event-announcement" to "## %event%\n%description%\n\nНачало: <t:%starts_at%:F> — <t:%starts_at%:R>\nУчастников: **%participants%**",
                "event-reminder" to "Событие **%event%** начнётся <t:%starts_at%:R>.",
                "event-result" to "## %event% — итоги\nПобедитель: **%winner%**",
                "security-link-changed" to "Привязка аккаунта **%player%** перенесена на другой Discord. Если это были не вы, немедленно сообщите администрации RusCrafting.",
                "security-link-pending" to "Запрошен перенос привязки аккаунта **%player%** на другой Discord. Если это не вы, отмените перенос в течение %seconds% сек.",
                "security-link-cancel" to "Отменить перенос",
                "security-link-cancelled" to "Перенос привязки отменён. Старый Discord остаётся связан с аккаунтом.",
                "security-link-cancel-missed" to "Этот запрос уже завершён или отменён.",
                "security-link-delivery-failed-alert" to "Перенос Discord-привязки **%player%** отменён: старому аккаунту не доставлена кнопка подтверждения.",
                "security-link-cancelled-alert" to "Перенос Discord-привязки **%player%** отменён старым аккаунтом.",
                "security-unlinked" to "Discord был отвязан от аккаунта **%player%**. Если это были не вы, немедленно сообщите администрации RusCrafting.",
                "moderation-status-issued" to "выдано",
                "moderation-status-expired" to "истекло",
                "moderation-status-removed" to "снято",
                "moderation-duration-permanent" to " (навсегда)",
                "moderation-duration-until" to " • до <t:%ends_at%:F>",
                "moderation-executor" to " • модератор: **%executor%**",
                "moderation-alert" to "Модерация: **%type%** %status% для **%player%**%duration%%moderator%",
                "mention-dm" to "Вас упомянули в игровом чате:\n> %message%",
                "auction-sold-dm" to "На аукционе продан **%item%** × %amount% за **%price%**. Покупатель: **%buyer%**.",
                "ticket-reply-dm" to "В заявке **%ticket%** появился ответ. Открыть: %url%",
                "punishment-dm" to "Наказание **%type%** для **%player%**: %status%.",
                "invite-dm" to "Вас пригласили: %message%",
            )
    }
}
