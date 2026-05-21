package com.typewritermc.quest.kukeui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KukeUiQuestPayloadTest : FunSpec({
    test("typewriter_quests sync payload exposes only clean quest schema and objective progress") {
        val payload = KukeUiQuestPayloadBuilder.buildDashboardJson(
            playerName = "Kuke",
            quests = listOf(
                KukeUiQuestCard(
                    id = "quest_intro",
                    title = "黄昏初访",
                    subtitle = "与守夜人交谈 1/2",
                    progress = 50,
                    status = "ACTIVE",
                    statusValue = 1,
                    tracked = false,
                    rewardSummary = "经验 +100",
                    objectives = listOf(
                        KukeUiQuestObjective(
                            id = "talk_guard",
                            text = "与守夜人交谈",
                            current = 1,
                            required = 2,
                            progress = 50,
                            completed = false,
                        )
                    )
                )
            )
        )

        val questJson = payload.substringAfter("\"quests\":[{").substringBefore("}]}")
        listOf(
            "id",
            "title",
            "description",
            "progress",
            "rewardSummary",
            "status",
            "statusValue",
            "tracked",
            "trackable",
            "untrackable",
            "objectives",
        ).forEach { field -> questJson shouldContainJson "\"$field\"" }
        payload shouldContainJson "\"playerName\":\"Kuke\""
        payload shouldContainJson "\"questProvider\":\"typewriter\""
        payload shouldContainJson "\"id\":\"quest_intro\""
        payload shouldContainJson "\"status\":\"ACTIVE\""
        payload shouldContainJson "\"statusValue\":1"
        payload shouldContainJson "\"tracked\":false"
        payload shouldContainJson "\"trackable\":true"
        payload shouldContainJson "\"untrackable\":false"
        payload shouldContainJson "\"rewardSummary\":\"经验 +100\""
        payload shouldContainJson "\"objectives\":[{"
        payload shouldContainJson "\"text\":\"与守夜人交谈\""
        payload shouldContainJson "\"progress\":50"
        payload shouldNotContainJson "\"typewriterEntryId\""
        payload shouldNotContainJson "\"questId\""
        payload shouldNotContainJson "\"reward\""
        payload shouldNotContainJson "\"accepted\""
        payload shouldNotContainJson "\"claimed\""
        payload shouldNotContainJson "\"canTrack\""
        payload shouldNotContainJson "\"canUntrack\""
        payload shouldNotContainJson "\"canStart\""
        payload shouldNotContainJson "\"canAbandon\""
        payload shouldNotContainJson "\"canClaim\""
        payload shouldNotContainJson "\"source\""
        payload shouldNotContainJson "\"subtitle\""
    }

    test("objective progress extractor reads current required progress and completed members") {
        val progress = KukeUiObjectiveProgressExtractor.extractWithoutPlayer(
            ReflectiveObjective(current = 4, required = 10, progress = 40, completed = false)
        )

        progress.current shouldBe 4
        progress.required shouldBe 10
        progress.progress shouldBe 40
        progress.completed shouldBe false
    }

    test("objective progress extractor derives completion and percentage from current and required") {
        val progress = KukeUiObjectiveProgressExtractor.extractWithoutPlayer(
            ReflectiveObjective(current = 12, required = 10)
        )

        progress.current shouldBe 12
        progress.required shouldBe 10
        progress.progress shouldBe 100
        progress.completed shouldBe true
    }

    test("objective progress extractor exports interact objective style progress as current required percentage and completion") {
        val progress = KukeUiObjectiveProgressExtractor.extractWithoutPlayer(
            InteractObjectiveLike(current = 1, required = 5)
        )

        progress.current shouldBe 1
        progress.required shouldBe 5
        progress.progress shouldBe 20
        progress.completed shouldBe false
    }

    test("objective progress extractor defaults unsupported objectives to zero progress") {
        val progress = KukeUiObjectiveProgressExtractor.extractWithoutPlayer(
            UnsupportedObjective
        )

        progress.current shouldBe 0
        progress.required shouldBe 0
        progress.progress shouldBe 0
        progress.completed shouldBe false
    }

    test("completed Typewriter quest is neither trackable nor untrackable") {
        val quest = KukeUiQuestCard(
            id = "quest_done",
            title = "完成的委托",
            subtitle = "已完成",
            progress = 100,
            status = "COMPLETED",
            statusValue = -1,
            tracked = false,
        )

        quest.trackable shouldBe false
        quest.untrackable shouldBe false
    }

    test("active tracked quest uses status value 2 and is untrackable") {
        val quest = KukeUiQuestCard(
            id = "tracked",
            title = "追踪中的任务",
            subtitle = "正在追踪",
            progress = 20,
            status = "ACTIVE",
            statusValue = 2,
            tracked = true,
        )

        quest.tracked shouldBe true
        quest.trackable shouldBe false
        quest.untrackable shouldBe true
    }

    test("npc quest state metadata exposes KukeUI entry states") {
        val payload = KukeUiQuestPayloadBuilder.buildDashboardJson(
            playerName = "Kuke",
            quests = listOf(
                KukeUiQuestCard(
                    id = "quest_ready",
                    title = "回报守夜人",
                    subtitle = "可提交",
                    progress = 100,
                    status = "ACTIVE",
                    statusValue = 1,
                    tracked = false,
                    npcState = KukeUiNpcQuestStateMetadata(
                        state = KukeUiNpcQuestState.READY_TO_SUBMIT,
                        questIds = listOf("quest_ready"),
                    ),
                )
            )
        )

        payload shouldContainJson "\"npcState\":{"
        payload shouldContainJson "\"state\":\"READY_TO_SUBMIT\""
        payload shouldContainJson "\"questIds\":[\"quest_ready\"]"
    }

    test("KukeUI bridge registration remains retryable when facade class is not loaded yet") {
        KukeUiQuestBridge.resetForTest()

        KukeUiQuestBridge.ensureRegistered()

        KukeUiQuestBridge.isRegisteredForTest() shouldBe false
    }
})

private data class ReflectiveObjective(
    val current: Int,
    val required: Int,
    val progress: Int? = null,
    val completed: Boolean? = null,
)

private object UnsupportedObjective

private data class InteractObjectiveLike(
    private val current: Int,
    private val required: Int,
) {
    fun current(): Int = current
    fun required(): Int = required
    fun progress(): Int = ((current.toDouble() / required.toDouble()) * 100).toInt()
    fun completed(): Boolean = current >= required
}

private infix fun String.shouldContainJson(fragment: String) {
    contains(fragment) shouldBe true
}

private infix fun String.shouldNotContainJson(fragment: String) {
    contains(fragment) shouldBe false
}
