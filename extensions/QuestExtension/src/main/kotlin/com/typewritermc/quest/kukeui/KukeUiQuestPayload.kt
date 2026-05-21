package com.typewritermc.quest.kukeui

import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.priority
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.engine.paper.entry.inAudience
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.facts.FactUpdateContext
import com.typewritermc.engine.paper.plugin
import com.typewritermc.quest.QuestStatus
import com.typewritermc.quest.completedQuests
import com.typewritermc.quest.entries.ObjectiveEntry
import com.typewritermc.quest.entries.QuestEntry
import com.typewritermc.quest.entries.audience.SimpleQuestEntry
import com.typewritermc.quest.entries.audience.objectives.CompositeTarget
import com.typewritermc.quest.entries.audience.objectives.ExactTarget
import com.typewritermc.quest.entries.audience.objectives.LowerBoundTarget
import com.typewritermc.quest.entries.audience.objectives.RangeTarget
import com.typewritermc.quest.entries.audience.objectives.TargetSpec
import com.typewritermc.quest.entries.audience.objectives.UpperBoundTarget
import com.typewritermc.quest.entries.interfaces.CachableFactObjective
import com.typewritermc.quest.entries.interfaces.ObjectiveProgress
import com.typewritermc.quest.events.AsyncQuestStatusUpdate
import com.typewritermc.quest.events.AsyncTrackedQuestUpdate
import com.typewritermc.quest.inactiveQuests
import com.typewritermc.quest.isQuestActive
import com.typewritermc.quest.isQuestTracked
import com.typewritermc.quest.trackQuest
import com.typewritermc.quest.trackedQuest
import com.typewritermc.quest.unTrackQuest
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val KUKEUI_FACADE = "kuke.kukeui.KukeUI"
private const val KUKEUI_PACKET_EVENT = "kuke.kukeui.event.UIPacketEvent"
private const val NAMESPACE = "typewriter_quests"

data class KukeUiQuestObjective(
    val id: String,
    val text: String,
    val current: Int = 0,
    val required: Int = 0,
    val progress: Int = 0,
    val completed: Boolean = false,
)

enum class KukeUiNpcQuestState {
    AVAILABLE,
    IN_PROGRESS,
    READY_TO_SUBMIT,
    COMPLETED,
    LOCKED,
}

data class KukeUiNpcQuestStateMetadata(
    val state: KukeUiNpcQuestState,
    val questIds: List<String> = emptyList(),
)

data class KukeUiQuestCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val progress: Int,
    val status: String,
    val statusValue: Int,
    val tracked: Boolean,
    val objectives: List<KukeUiQuestObjective> = emptyList(),
    val description: String = "",
    val rewardSummary: String = "",
    val npcState: KukeUiNpcQuestStateMetadata? = null,
) {
    val trackable: Boolean get() = statusValue > 0 && !tracked
    val untrackable: Boolean get() = statusValue > 0 && tracked
}

object KukeUiObjectiveProgressExtractor {
    fun extractWithoutPlayer(objective: Any): KukeUiQuestObjective = extractReflective(objective)

    fun extract(objective: Any, player: Player?): KukeUiQuestObjective {
        if (objective is ObjectiveProgress && player != null) return extractObjectiveProgress(objective, player)
        if (player == null) return extractReflective(objective)
        val current = readInt(objective, player, "current", "count", "value") ?: 0
        val required = readInt(objective, player, "required", "target") ?: requiredFromTargetSpec(objective, player) ?: 0
        val progress = readInt(objective, player, "progress", "percentage") ?: percent(current, required)
        val completed = readBoolean(objective, player, "completed", "complete", "isCompleted") ?: completed(current, required)
        return KukeUiQuestObjective(
            id = readString(objective, "id") ?: "",
            text = "",
            current = current.coerceAtLeast(0),
            required = required.coerceAtLeast(0),
            progress = progress.coerceIn(0, 100),
            completed = completed,
        )
    }

    private fun extractObjectiveProgress(objective: ObjectiveProgress, player: Player): KukeUiQuestObjective {
        val current = objective.current(player)
        val required = objective.required(player)
        return KukeUiQuestObjective(
            id = readString(objective, "id") ?: "",
            text = "",
            current = current.coerceAtLeast(0),
            required = required.coerceAtLeast(0),
            progress = objective.progress(player).coerceIn(0, 100),
            completed = objective.completed(player),
        )
    }

    private fun extractReflective(objective: Any): KukeUiQuestObjective {
        val current = readIntNoPlayer(objective, "current", "count", "value") ?: 0
        val required = readIntNoPlayer(objective, "required", "target") ?: 0
        val progress = readIntNoPlayer(objective, "progress", "percentage") ?: percent(current, required)
        val completed = readBooleanNoPlayer(objective, "completed", "complete", "isCompleted") ?: completed(current, required)
        return KukeUiQuestObjective(
            id = readString(objective, "id") ?: "",
            text = "",
            current = current.coerceAtLeast(0),
            required = required.coerceAtLeast(0),
            progress = progress.coerceIn(0, 100),
            completed = completed,
        )
    }

    private fun requiredFromTargetSpec(objective: Any, player: Player?): Int? {
        val raw = readVarValue(objective, player, "target") as? String ?: return null
        return TargetSpec.parse(raw).requiredValue()
    }

    private fun TargetSpec.requiredValue(): Int? = when (this) {
        is ExactTarget -> value
        is RangeTarget -> min
        is LowerBoundTarget -> min
        is UpperBoundTarget -> max
        is CompositeTarget -> specs.mapNotNull { it.requiredValue() }.minOrNull()
        else -> null
    }

    private fun percent(current: Int, required: Int): Int {
        if (required <= 0) return 0
        return ((current.toDouble() / required.toDouble()) * 100).toInt()
    }

    private fun completed(current: Int, required: Int): Boolean = required > 0 && current >= required

    private fun readString(target: Any, name: String): String? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target) as? String
    }.getOrNull()

    private fun readInt(target: Any, player: Player?, vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        readValue(target, player, name)?.toIntOrNull()
    }

    private fun readBoolean(target: Any, player: Player?, vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
        readValue(target, player, name)?.toBooleanOrNull()
    }

    private fun readIntNoPlayer(target: Any, vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        (callMember(target, name) ?: callMember(target, "get${name.replaceFirstChar(Char::uppercase)}"))?.toIntOrNull()
    }

    private fun readBooleanNoPlayer(target: Any, vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
        (callMember(target, name) ?: callMember(target, "get${name.replaceFirstChar(Char::uppercase)}"))?.toBooleanOrNull()
    }

    private fun readValue(target: Any, player: Player?, name: String): Any? {
        val direct = callMember(target, name) ?: callMember(target, "get${name.replaceFirstChar(Char::uppercase)}")
        return unwrapValue(direct, player)
    }

    private fun readVarValue(target: Any, player: Player?, name: String): Any? = unwrapValue(callMember(target, name), player)

    private fun callMember(target: Any, name: String): Any? = runCatching {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
    }.getOrNull()

    private fun unwrapValue(value: Any?, player: Player?): Any? = when {
        value is Var<*> && player != null -> runCatching { value.get(player) }.getOrNull()
        else -> value
    }

    private fun Any.toIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }

    private fun Any.toBooleanOrNull(): Boolean? = when (this) {
        is Boolean -> this
        is String -> toBooleanStrictOrNull()
        else -> null
    }
}

object KukeUiQuestPayloadBuilder {
    fun buildDashboardJson(
        playerName: String,
        quests: List<KukeUiQuestCard>,
        onlinePlayers: Int = 0,
    ): String = buildString {
        append('{')
        appendProperty("playerName", playerName)
        append(',')
        appendProperty("serverName", "Typewriter")
        append(',')
        appendProperty("status", "Typewriter QuestExtension")
        append(',')
        append("\"onlinePlayers\":").append(onlinePlayers.coerceAtLeast(0))
        append(',')
        append("\"fpsHint\":0")
        append(',')
        appendProperty("questProvider", "typewriter")
        append(',')
        append("\"quests\":[")
        quests.forEachIndexed { index, quest ->
            if (index > 0) append(',')
            appendQuest(quest)
        }
        append(']')
        append('}')
    }

    private fun StringBuilder.appendQuest(quest: KukeUiQuestCard) {
        append('{')
        appendProperty("id", quest.id)
        append(',')
        appendProperty("title", quest.title)
        append(',')
        append("\"progress\":").append(quest.progress.coerceIn(0, 100))
        append(',')
        appendProperty("rewardSummary", quest.rewardSummary)
        append(',')
        appendProperty("description", quest.description)
        append(',')
        appendProperty("status", quest.status)
        append(',')
        append("\"statusValue\":").append(quest.statusValue)
        append(',')
        append("\"tracked\":").append(quest.tracked)
        append(',')
        append("\"trackable\":").append(quest.trackable)
        append(',')
        append("\"untrackable\":").append(quest.untrackable)
        append(',')
        appendNpcState(quest.npcState)
        append(',')
        append("\"objectives\":[")
        quest.objectives.forEachIndexed { index, objective ->
            if (index > 0) append(',')
            appendObjective(objective)
        }
        append(']')
        append('}')
    }

    private fun StringBuilder.appendNpcState(metadata: KukeUiNpcQuestStateMetadata?) {
        if (metadata == null) {
            append("\"npcState\":null")
            return
        }
        append("\"npcState\":{")
        appendProperty("state", metadata.state.name)
        append(',')
        append("\"questIds\":[")
        metadata.questIds.forEachIndexed { index, questId ->
            if (index > 0) append(',')
            append('"').append(escapeJson(questId)).append('"')
        }
        append("]}")
    }

    private fun StringBuilder.appendObjective(objective: KukeUiQuestObjective) {
        append('{')
        appendProperty("id", objective.id)
        append(',')
        appendProperty("text", objective.text)
        append(',')
        append("\"current\":").append(objective.current)
        append(',')
        append("\"required\":").append(objective.required)
        append(',')
        append("\"progress\":").append(objective.progress.coerceIn(0, 100))
        append(',')
        append("\"completed\":").append(objective.completed)
        append('}')
    }

    private fun StringBuilder.appendProperty(key: String, value: String) {
        append('"').append(escapeJson(key)).append("\":\"").append(escapeJson(value)).append('"')
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }
}

object KukeUiQuestBridge : Listener {
    private var registered = false
    private var kukeUiClass: Class<*>? = null
    private var hasModMethod: Method? = null
    private var sendRawMethod: Method? = null

    @Synchronized
    fun ensureRegistered() {
        if (registered) return
        val kukeUi = loadKukeUiClass() ?: return
        val didRegister = runCatching {
            kukeUi.getMethod("register", String::class.java, org.bukkit.plugin.Plugin::class.java)
                .invoke(null, NAMESPACE, com.typewritermc.engine.paper.plugin)
        }.isSuccess
        if (!didRegister) return
        registered = true
        registerPacketListener()
    }

    internal fun isRegisteredForTest(): Boolean = registered

    @Synchronized
    internal fun resetForTest() {
        registered = false
        kukeUiClass = null
        hasModMethod = null
        sendRawMethod = null
    }

    fun sync(player: Player) {
        ensureRegistered()
        if (!hasMod(player)) return
        val quests = collectQuestCards(player)
        val payload = KukeUiQuestPayloadBuilder.buildDashboardJson(
            playerName = player.name,
            quests = quests,
            onlinePlayers = Bukkit.getOnlinePlayers().size,
        ).toByteArray(StandardCharsets.UTF_8)
        sendRaw(player, "sync", payload)
    }

    fun syncAll() {
        Bukkit.getOnlinePlayers().forEach(::sync)
    }

    private fun collectQuestCards(player: Player): List<KukeUiQuestCard> {
        val inactive = player.inactiveQuests().toSet()
        val completed = player.completedQuests().toSet()
        val tracked = player.trackedQuest()
        return Query.find<QuestEntry>()
            .map { quest ->
                val ref = quest.ref()
                val status = when {
                    ref in completed -> QuestStatus.COMPLETED
                    ref in inactive -> QuestStatus.INACTIVE
                    else -> QuestStatus.ACTIVE
                }
                val isTracked = tracked == ref || (player isQuestTracked ref)
                val objectives = collectObjectives(player, ref, status)
                KukeUiQuestCard(
                    id = quest.id,
                    title = quest.display(player),
                    subtitle = subtitleFor(status, isTracked, objectives),
                    progress = progressFor(status, objectives),
                    status = status.name,
                    statusValue = statusValue(status, isTracked),
                    tracked = isTracked,
                    objectives = objectives,
                )
            }
            .sortedWith(compareByDescending<KukeUiQuestCard> { it.tracked }
                .thenBy { sortRank(it.status) }
                .thenBy { it.title })
            .toList()
    }

    private fun collectObjectives(
        player: Player,
        quest: Ref<QuestEntry>,
        status: QuestStatus,
    ): List<KukeUiQuestObjective> {
        if (status != QuestStatus.ACTIVE) return emptyList()
        return Query.findWhere<ObjectiveEntry> { objective ->
            objective.quest == quest && player.inAudience(objective.ref())
        }
            .sortedByDescending { objective -> objective.priority }
            .map { objective ->
                KukeUiObjectiveProgressExtractor.extract(objective, player).copy(
                    id = objective.id,
                    text = objective.display(player),
                )
            }
            .toList()
    }

    private fun subtitleFor(
        status: QuestStatus,
        tracked: Boolean,
        objectives: List<KukeUiQuestObjective>,
    ): String = when {
        status == QuestStatus.COMPLETED -> "已完成"
        tracked && objectives.isNotEmpty() -> objectives.first().text
        tracked -> "追踪中"
        status == QuestStatus.ACTIVE && objectives.isNotEmpty() -> objectives.first().text
        status == QuestStatus.ACTIVE -> "进行中"
        else -> "未激活"
    }

    private fun progressFor(status: QuestStatus, objectives: List<KukeUiQuestObjective>): Int = when {
        status == QuestStatus.COMPLETED -> 100
        status == QuestStatus.INACTIVE -> 0
        objectives.isEmpty() -> 0
        else -> objectives.map { it.progress }.average().toInt().coerceIn(0, 100)
    }

    private fun statusValue(status: QuestStatus, tracked: Boolean): Int = when (status) {
        QuestStatus.INACTIVE -> 0
        QuestStatus.ACTIVE -> if (tracked) 2 else 1
        QuestStatus.COMPLETED -> -1
    }

    private fun sortRank(status: String): Int = when (status) {
        "ACTIVE" -> 0
        "INACTIVE" -> 1
        "COMPLETED" -> 2
        else -> 3
    }

    private fun hasMod(player: Player): Boolean = runCatching {
        val method = hasModMethod ?: loadKukeUiClass()?.getMethod("hasMod", Player::class.java)?.also {
            hasModMethod = it
        } ?: return false
        method.invoke(null, player) as? Boolean ?: false
    }.getOrDefault(false)

    private fun sendRaw(player: Player, action: String, payload: ByteArray): Boolean = runCatching {
        val method = sendRawMethod ?: loadKukeUiClass()
            ?.methods
            ?.firstOrNull { method ->
                method.name == "sendRaw" && method.parameterTypes.contentEquals(
                    arrayOf(Player::class.java, String::class.java, String::class.java, ByteArray::class.java)
                )
            }
            ?.also { sendRawMethod = it } ?: return false
        method.invoke(null, player, NAMESPACE, action, payload)
        true
    }.getOrDefault(false)

    private fun registerPacketListener() {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val eventClass = loadKukeUiEventClass() as? Class<out Event> ?: return@runCatching
            Bukkit.getPluginManager().registerEvent(
                eventClass,
                this,
                EventPriority.NORMAL,
                { _, event -> handlePacket(event) },
                com.typewritermc.engine.paper.plugin,
                false,
            )
        }
    }

    private fun handlePacket(event: Event) {
        if (!isKukeUiPacketEvent(event)) return
        val namespace = event.callString("getNamespace") ?: return
        if (namespace != NAMESPACE) return
        val player = event.callPlayer("getPlayer") ?: return
        val action = event.callString("getAction") ?: return
        val payload = event.callByteArray("getPayload") ?: ByteArray(0)
        Bukkit.getScheduler().runTask(com.typewritermc.engine.paper.plugin, Runnable {
            runCatching { handleAction(player, action, payload) }
        })
    }

    private fun handleAction(player: Player, action: String, payload: ByteArray) {
        when (action) {
            "track" -> {
                val questId = readId(payload) ?: return
                val quest = Query.findById<QuestEntry>(questId)?.ref() ?: return
                if (!(player isQuestActive quest)) return
                player trackQuest quest
                sync(player)
            }
            "untrack" -> {
                val questId = readId(payload) ?: return
                val trackedQuest = player.trackedQuest() ?: return
                if (trackedQuest.id != questId) return
                player.unTrackQuest()
                sync(player)
            }
        }
    }

    private fun readId(payload: ByteArray): String? = runCatching {
        if (payload.isEmpty()) return null
        val text = payload.toString(StandardCharsets.UTF_8).trim()
        Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.getOrNull(1)
    }.getOrNull()

    private fun loadKukeUiClass(): Class<*>? {
        if (kukeUiClass != null) return kukeUiClass
        return runCatching { Class.forName(KUKEUI_FACADE) }.getOrNull()?.also { kukeUiClass = it }
    }

    private fun loadKukeUiEventClass(): Class<*>? = runCatching { Class.forName(KUKEUI_PACKET_EVENT) }.getOrNull()
    private fun isKukeUiPacketEvent(event: Event): Boolean = loadKukeUiEventClass()?.isInstance(event) == true
}

private fun Event.callString(methodName: String): String? = runCatching {
    javaClass.getMethod(methodName).invoke(this) as? String
}.getOrNull()

private fun Event.callPlayer(methodName: String): Player? = runCatching {
    javaClass.getMethod(methodName).invoke(this) as? Player
}.getOrNull()

private fun Event.callByteArray(methodName: String): ByteArray? = runCatching {
    javaClass.getMethod(methodName).invoke(this) as? ByteArray
}.getOrNull()

object KukeUiQuestProgressSync {
    private val pending = ConcurrentHashMap.newKeySet<UUID>()

    fun schedule(player: Player) {
        if (!pending.add(player.uniqueId)) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            pending.remove(player.uniqueId)
            KukeUiQuestBridge.sync(player)
        }, 2L)
    }
}

@EntryListener(SimpleQuestEntry::class)
fun onKukeUiQuestStatusUpdate(event: AsyncQuestStatusUpdate) {
    KukeUiQuestBridge.sync(event.player)
}

@EntryListener(SimpleQuestEntry::class)
fun onKukeUiTrackedQuestUpdate(event: AsyncTrackedQuestUpdate) {
    KukeUiQuestBridge.sync(event.player)
}

fun onKukeUiObjectiveFactProgressUpdate(context: FactUpdateContext) {
    val affectsObjective = Query.find<ObjectiveEntry>().any { objective ->
        when (objective) {
            is CachableFactObjective -> objective.progressTracking.value.id == context.ref.id
            else -> objective.criteria.any { it.fact.id == context.ref.id }
        }
    }
    if (affectsObjective) KukeUiQuestProgressSync.schedule(context.player)
}
