package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.nio.file.Files
import java.util.UUID

class DiscordVerificationListenerTest : FreeSpec({
    "successful role sync explains when Discord hierarchy prevents only the nickname" {
        val listener =
            DiscordVerificationListener(
                verificationConfig(Files.createTempDirectory("discord-verification-listener")),
                mockk(),
            )
        val result =
            DiscordVerificationWorkflowResult.Verified(
                link = DiscordIdentityLink(UUID.randomUUID(), "GrocerMC", "123456789012345678", 1, 1),
                idempotent = false,
                reconciliation =
                    DiscordRoleReconcileResult(
                        status = DiscordRoleReconcileResult.Status.UPDATED,
                        addedRoleIds = setOf("1083092420394221699", "1079926438804848660"),
                        nicknameSkipped = true,
                        reason = "nickname-hierarchy",
                    ),
            )

        listener.resultMessage(result) shouldBe
            "Discord • Аккаунт Minecraft GrocerMC подтверждён. " +
                "Роли синхронизированы. Ник не изменён: участник выше бота в Discord."
    }
})
