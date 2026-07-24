package ru.arc.ai.tickets

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class IssueTicketTitlesTest :
    StringSpec({
        "markClosed prefixes title once" {
            IssueTicketTitles.markClosed("rtp не работает") shouldStartWith IssueTicketTitles.CLOSED_PREFIX
            IssueTicketTitles.markClosed("rtp не работает") shouldBe "[Закрыт] rtp не работает"
        }

        "markClosed strips existing closed prefix" {
            IssueTicketTitles.markClosed("[Закрыт] rtp") shouldBe "[Закрыт] rtp"
        }
    })
