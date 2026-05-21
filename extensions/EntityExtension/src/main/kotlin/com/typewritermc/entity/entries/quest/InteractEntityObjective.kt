package com.typewritermc.entity.entries.quest

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.utils.point.Position
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entity.AudienceEntityDisplay
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.findDisplay
import com.typewritermc.engine.paper.entry.triggerEntriesFor
import com.typewritermc.engine.paper.events.AsyncEntityDefinitionInteract
import com.typewritermc.engine.paper.entry.PlaceholderParser
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.position
import com.typewritermc.quest.entries.ObjectiveAudienceFilter
import com.typewritermc.quest.entries.QuestEntry
import com.typewritermc.quest.entries.interfaces.CachableFactObjective
import com.typewritermc.quest.entries.interfaces.CacheableFactObjectiveProgressTracking
import com.typewritermc.quest.entries.interfaces.LocatableObjective
import com.typewritermc.roadnetwork.entries.PathStreamDisplayEntry
import com.typewritermc.roadnetwork.entries.StreamProducer
import org.bukkit.entity.Player
import java.util.*

private val displayTemplate by snippet("quest.objective.interact_entity", "Interact with <entity>")

@Entry("interact_entity_objective", "Interact with an entity", Colors.BLUE_VIOLET, "ph:hand-tap-fill")
/**
 * The `InteractEntityObjective` class is an entry that represents an objective to interact with an entity.
 * When such an objective is active, it will show an icon above any NPC.
 */
class InteractEntityObjective(
    override val id: String = "",
    override val name: String = "",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    override val quest: Ref<QuestEntry> = emptyRef(),
    override val criteria: List<Criteria> = emptyList(),
    @Help("The entity definition that the player needs to interact with.")
    val entity: Ref<out EntityDefinitionEntry> = emptyRef(),
    @Help("Optional concrete NPC/entity instance that must be clicked. Leave empty to allow any instance of the entity definition.")
    val instance: Ref<out EntityInstanceEntry> = emptyRef(),
    @Help("Track the progress of the InteractEntityObjective using a fact and set its target value.")
    override val progressTracking: CacheableFactObjectiveProgressTracking = CacheableFactObjectiveProgressTracking(),
    @Help("The entries that will trigger once the objective is completed.")
    override val completionTriggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The objective display that will be shown to the player. Use &lt;entity&gt; to replace the entity name.")
    val overrideDisplay: Optional<Var<String>> = Optional.empty(),
    override val priorityOverride: Optional<Int> = Optional.empty(),
) : LocatableObjective, CachableFactObjective {
    override val display: Var<String>
        get() = overrideDisplay.orElseGet { ConstVar(displayTemplate) }
            .map { player, value -> value.replace("<entity>", entity.get()?.displayName?.get(player) ?: "") }

    private val displays by lazy(LazyThreadSafetyMode.NONE) {
        // As displays and references can't change (except between reloads) we can just cache all relevant ones here for quick access.
        Query.findWhere<EntityInstanceEntry> { it.definition == entity }
            .mapNotNull { it.ref().findDisplay<AudienceEntityDisplay>() }
            .toList()
    }

    override fun positions(player: Player?): List<Position> {
        if (player == null) return emptyList()
        return displays.filter { it.canView(player.uniqueId) }
            .mapNotNull { it.position(player.uniqueId) }
    }

    override fun streamProducers(player: Player, pathStreamDisplay: Ref<PathStreamDisplayEntry>): List<StreamProducer> {
        return displays.filter { it.canView(player.uniqueId) }
            .map { display ->
                StreamProducer(
                    "${id}_${display.instanceEntryRef.id}",
                    pathStreamDisplay,
                    endPosition = { display.position(player.uniqueId) ?: it.position })
            }
    }

    override suspend fun display(): AudienceFilter {
        return ObjectiveAudienceFilter(ref(), criteria)
            .listenToEvent(AsyncEntityDefinitionInteract::class) { event ->
                handleEntityInteract(event.player, event.definition, event.instance, event.hand, event.action)
            }
    }

    fun handleEntityInteract(
        player: Player,
        definition: EntityDefinitionEntry,
        instance: EntityInstanceEntry,
        hand: InteractionHand,
        action: InteractAction,
    ) {
        if (hand != InteractionHand.MAIN_HAND || action == InteractAction.INTERACT) return
        if (!matches(definition, instance)) return
        incrementProgress(player, 1)
    }

    override fun current(player: Player): Int = progressTracking.value.get()
        ?.readForPlayersGroup(player)
        ?.value
        ?.coerceAtMost(required(player))
        ?: 0

    override fun required(player: Player): Int = progressTracking.target.get(
        player,
        com.typewritermc.core.interaction.context()
    ).coerceAtLeast(0)

    override fun progress(player: Player): Int {
        val required = required(player)
        if (required <= 0) return 0
        return ((current(player).toDouble() / required.toDouble()) * 100).toInt().coerceIn(0, 100)
    }

    override fun completed(player: Player): Boolean {
        val fact = progressTracking.value.get()?.readForPlayersGroup(player)
        return progressTracking.isValid(fact, player, com.typewritermc.core.interaction.context())
    }

    override fun parser(): PlaceholderParser = super<CachableFactObjective>.parser()

    private fun matches(definition: EntityDefinitionEntry, instance: EntityInstanceEntry): Boolean {
        if (entity.isSet && definition.ref() != entity) return false
        if (this.instance.isSet && instance.ref() != this.instance) return false
        return true
    }

    private fun incrementProgress(player: Player, amount: Int) {
        val fact = progressTracking.value.get() ?: return
        val before = fact.readForPlayersGroup(player)
        val completedBefore = completed(player)
        val target = required(player)
        val next = (before.value + amount).let { value ->
            if (target > 0) value.coerceAtMost(target) else value
        }
        if (next == before.value && completedBefore) return

        fact.write(player, next)
        if (!completedBefore && completed(player)) {
            completionTriggers.triggerEntriesFor(player, com.typewritermc.core.interaction.context())
        }
    }
}