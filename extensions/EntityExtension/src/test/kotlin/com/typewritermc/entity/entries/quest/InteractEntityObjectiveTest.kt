package com.typewritermc.entity.entries.quest

import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.entries.ref
import com.typewritermc.engine.paper.entry.CriteriaOperator
import com.typewritermc.engine.paper.entry.entries.CachableFactEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.EntityData
import com.typewritermc.engine.paper.entry.entries.EntityDefinitionEntry
import com.typewritermc.engine.paper.entry.entries.EntityInstanceEntry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.SoundEmitter
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.entries.PassThroughFilter
import com.typewritermc.engine.paper.entry.entity.EntityState
import com.typewritermc.engine.paper.entry.entity.FakeEntity
import com.typewritermc.engine.paper.entry.entity.PositionProperty
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.facts.FactId
import com.typewritermc.engine.paper.utils.Sound
import com.typewritermc.quest.entries.interfaces.CacheableFactObjectiveProgressTracking
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.entity.Player

class InteractEntityObjectiveTest : FunSpec({
    test("interacting with the configured entity definition advances objective progress") {
        val player = FakePlayer("Kuke")
        val matchingDefinition = FakeDefinition("guard", "Guard")
        val wrongDefinition = FakeDefinition("merchant", "Merchant")
        val matchingInstance = FakeInstance("guard_instance", matchingDefinition.ref())
        val progressFact = MemoryFact("talk_guard_progress")
        val objective = InteractEntityObjective(
            id = "talk_guard",
            entity = matchingDefinition.ref(),
            progressTracking = CacheableFactObjectiveProgressTracking(
                value = progressFact.ref(),
                operator = CriteriaOperator.GREATER_THAN_OR_EQUAL,
                target = ConstVar(1),
            ),
        )

        objective.handleEntityInteract(
            player = player,
            definition = wrongDefinition,
            instance = matchingInstance,
            hand = InteractionHand.MAIN_HAND,
            action = InteractAction.INTERACT_AT,
        )
        progressFact.readForPlayersGroup(player).value shouldBe 0

        objective.handleEntityInteract(
            player = player,
            definition = matchingDefinition,
            instance = matchingInstance,
            hand = InteractionHand.MAIN_HAND,
            action = InteractAction.INTERACT_AT,
        )

        progressFact.readForPlayersGroup(player).value shouldBe 1
        objective.current(player) shouldBe 1
        objective.required(player) shouldBe 1
        objective.progress(player) shouldBe 100
        objective.completed(player) shouldBe true
    }

    test("completed interact entity objective does not increment past target") {
        val player = FakePlayer("Kuke")
        val definition = FakeDefinition("guard", "Guard")
        val instance = FakeInstance("guard_instance", definition.ref())
        val progressFact = MemoryFact("talk_guard_capped_progress")
        val objective = InteractEntityObjective(
            id = "talk_guard_capped",
            entity = definition.ref(),
            progressTracking = CacheableFactObjectiveProgressTracking(
                value = progressFact.ref(),
                target = ConstVar(2),
            ),
        )

        repeat(5) {
            objective.handleEntityInteract(
                player = player,
                definition = definition,
                instance = instance,
                hand = InteractionHand.MAIN_HAND,
                action = InteractAction.INTERACT_AT,
            )
        }

        progressFact.readForPlayersGroup(player).value shouldBe 2
        objective.current(player) shouldBe 2
        objective.required(player) shouldBe 2
        objective.progress(player) shouldBe 100
        objective.completed(player) shouldBe true
    }

    test("interact entity objective can be bound to a specific NPC/entity instance") {
        val player = FakePlayer("Kuke")
        val definition = FakeDefinition("guard", "Guard")
        val expectedInstance = FakeInstance("guard_main", definition.ref())
        val otherInstance = FakeInstance("guard_clone", definition.ref())
        val progressFact = MemoryFact("talk_specific_guard_progress")
        val objective = InteractEntityObjective(
            id = "talk_specific_guard",
            entity = definition.ref(),
            instance = expectedInstance.ref(),
            progressTracking = CacheableFactObjectiveProgressTracking(
                value = progressFact.ref(),
                target = ConstVar(1),
            ),
        )

        objective.handleEntityInteract(
            player = player,
            definition = definition,
            instance = otherInstance,
            hand = InteractionHand.MAIN_HAND,
            action = InteractAction.INTERACT_AT,
        )
        progressFact.readForPlayersGroup(player).value shouldBe 0

        objective.handleEntityInteract(
            player = player,
            definition = definition,
            instance = expectedInstance,
            hand = InteractionHand.MAIN_HAND,
            action = InteractAction.INTERACT_AT,
        )

        progressFact.readForPlayersGroup(player).value shouldBe 1
        objective.completed(player) shouldBe true
    }
})

private class MemoryFact(
    override val id: String,
) : CachableFactEntry {
    override val name: String = id
    override val comment: String = ""
    override val group: Ref<GroupEntry> = emptyRef()
    private val values = mutableMapOf<String, FactData>()

    override fun read(id: FactId): FactData = values[id.groupId.id] ?: FactData(0)

    override fun write(id: FactId, value: Int) {
        values[id.groupId.id] = FactData(value)
    }
}

private class FakeDefinition(
    override val id: String,
    display: String,
) : EntityDefinitionEntry {
    override val name: String = id
    override val data: List<Ref<EntityData<*>>> = emptyList()
    override val displayName: Var<String> = ConstVar(display)
    override val sound: Var<Sound> = ConstVar(Sound.EMPTY)

    override fun create(player: Player): FakeEntity = FakeTestEntity(player)
}

private class FakeTestEntity(player: Player) : FakeEntity(player) {
    override val entityId: Int = 0
    override val state: EntityState = EntityState()

    override fun applyProperties(properties: List<com.typewritermc.engine.paper.entry.entries.EntityProperty>) = Unit
    override fun addPassenger(entity: FakeEntity) = Unit
    override fun removePassenger(entity: FakeEntity) = Unit
    override fun contains(entityId: Int): Boolean = false
    override fun spawn(location: PositionProperty) = Unit
}

private class FakeInstance(
    override val id: String,
    override val definition: Ref<out EntityDefinitionEntry>,
) : EntityInstanceEntry {
    override val name: String = id
    override val children = emptyList<Ref<out com.typewritermc.engine.paper.entry.entries.AudienceEntry>>()

    override suspend fun display() = PassThroughFilter(ref())
    override fun getEmitter(player: Player): SoundEmitter = SoundEmitter(0)
}

private class FakePlayer(name: String) : Player by io.mockk.mockk(relaxed = true) {
    private val uuid = java.util.UUID.nameUUIDFromBytes(name.toByteArray())

    override fun getUniqueId(): java.util.UUID = uuid
    override fun getName(): String = name
}
