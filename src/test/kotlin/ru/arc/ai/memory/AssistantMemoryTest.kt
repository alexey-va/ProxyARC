package ru.arc.ai.memory

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.arc.ai.tools.DefaultTools
import ru.arc.redis.InMemoryRedis
import java.util.concurrent.ConcurrentLinkedDeque

class AssistantMemoryStoreTest : FreeSpec({

    "remember and forget facts" {
        val store = AssistantMemoryStore(null)
        val saved = store.remember("grocermc", "любит токсить", 0.9)
        saved.subject shouldBe "grocermc"
        saved.confidence shouldBe 0.9

        store.forget(factContains = "токсить") shouldBe 1
        store.list() shouldHaveSize 0
    }

    "formatForPrompt filters by confidence" {
        val store = AssistantMemoryStore(null)
        store.remember("a", "low", 0.2)
        store.remember("b", "high", 0.95)
        val text = store.formatForPrompt(minConfidence = 0.5, maxFacts = 10)
        text shouldContain "high"
        text?.contains("low") shouldBe false
    }

    "persists to redis hash" {
        val redis = InMemoryRedis()
        val store = AssistantMemoryStore(redis, "test.facts")
        store.remember("steve", "построил базу", 0.8)

        val reloaded = AssistantMemoryStore(redis, "test.facts")
        reloaded.list() shouldHaveSize 1
        reloaded.list().first().fact shouldBe "построил базу"
    }

    "findByIdPrefix resolves unique prefix" {
        val store = AssistantMemoryStore(null)
        val saved = store.remember("a", "one", 0.5)
        store.findByIdPrefix(saved.id.take(8))?.fact shouldBe "one"
        store.findByIdPrefix(saved.id)?.fact shouldBe "one"
        store.findByIdPrefix("missing") shouldBe null
    }
})

class RememberFactSubjectTest : FreeSpec({

    "resolveSubject maps server keywords to general facts" {
        DefaultTools.resolveSubject("server", "grocermc") shouldBe null
        DefaultTools.resolveSubject("общее", "grocermc") shouldBe null
        DefaultTools.resolveSubject(null, "grocermc") shouldBe "grocermc"
        DefaultTools.resolveSubject("Steve", "grocermc") shouldBe "Steve"
    }
})

class AssistantHistoryCompactorTest : FreeSpec({

    "compactObservations merges overflow into summary line" {
        val lines = ConcurrentLinkedDeque<String>()
        repeat(5) { lines.addLast("line-$it") }
        AssistantHistoryCompactor.compactObservations(lines, maxLines = 3)
        lines.size shouldBe 3
        lines.first() shouldContain "сводка чата"
        lines.first() shouldContain "3 старых строк опущено"
    }
})
