package com.typewritermc.basic.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.triggerFor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.Locale

object KukeCoreBridge {
    private const val PLUGIN_NAME = "KukeCore"
    private val logger = java.util.logging.Logger.getLogger(KukeCoreBridge::class.java.name)

    fun invoke(player: Player, hook: KukeCoreHook, payload: KukeCorePayload): KukeCoreInvocationResult {
        if (!Bukkit.isPrimaryThread()) {
            return KukeCoreInvocationResult(false, "KukeCore bridge must run on Bukkit main thread")
        }
        val plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)
            ?: return KukeCoreInvocationResult(false, "KukeCore plugin is not loaded").also { auditFailure(player, hook, it.message) }
        val facade = runCatching { plugin.javaClass.getMethod("services").invoke(plugin) }
            .mapCatching { services -> services.javaClass.getMethod("bridgeFacade").invoke(services) }
            .getOrElse {
                val result = KukeCoreInvocationResult(false, "KukeCore bridgeFacade is not available")
                auditFailure(player, hook, result.message)
                return result
            }
        return hook.invoke(facade, player, payload).also {
            if (!it.success) auditFailure(player, hook, it.message)
        }
    }

    private fun auditFailure(player: Player, hook: KukeCoreHook, message: String) {
        logger.warning("KukeCore bridge failure player=${player.uniqueId} hook=$hook message=${message.redacted()}")
    }
}

private fun String.redacted(): String = replace(Regex("(?i)(secret|token|password|key)=\\S+"), "$1=<redacted>")

data class KukeCoreInvocationResult(val success: Boolean, val message: String = "")

data class KukeCorePayload(
    val amountLong: Long = 0L,
    val amountDouble: Double = 0.0,
    val type: String = "vanilla",
    val id: String = "",
    val amount: Int = 1,
    val title: String = "Typewriter Reward",
    val body: String = "",
    val attachments: List<KukeCoreMailAttachment> = emptyList(),
    val idempotencyKey: String = "",
)

data class KukeCoreMailAttachment(
    val type: String = "vanilla",
    val id: String = "",
    val amount: Int = 1,
)

enum class KukeCoreHook {
    GIVE_EXP,
    GIVE_MONEY,
    GIVE_ITEM,
    GIVE_EQUIPMENT,
    SEND_MAIL,
    UNLOCK_SKILL,
    START_DUNGEON;

    fun invoke(facade: Any, player: Player, payload: KukeCorePayload): KukeCoreInvocationResult = when (this) {
        GIVE_EXP -> call(facade, "giveExp", player, payload.amountLong, payload.idempotencyKey)
        GIVE_MONEY -> call(facade, "giveMoney", player, payload.amountDouble, payload.idempotencyKey)
        GIVE_ITEM -> call(facade, "giveItem", player, payload.type, payload.id, payload.amount, payload.idempotencyKey)
        GIVE_EQUIPMENT -> call(facade, "giveEquipment", player, payload.id, payload.amount, payload.idempotencyKey)
        SEND_MAIL -> call(
            facade,
            "sendMail",
            player,
            payload.title,
            payload.body,
            payload.amountDouble,
            payload.attachments.toKukeAttachments(),
            payload.idempotencyKey,
        )
        UNLOCK_SKILL -> call(facade, "unlockSkill", player, payload.id, payload.idempotencyKey)
        START_DUNGEON -> call(facade, "startDungeon", player, payload.id, payload.idempotencyKey)
    }

    private fun call(facade: Any, methodName: String, vararg args: Any): KukeCoreInvocationResult = runCatching {
        val method = facade.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == args.size }
            ?: return KukeCoreInvocationResult(false, "KukeCore bridge method missing: $methodName/${args.size}")
        method.invoke(facade, *args).toInvocationResult()
    }.getOrElse { KukeCoreInvocationResult(false, it.cause?.message ?: it.message ?: it::class.simpleName.orEmpty()) }
}

private fun Any?.toInvocationResult(): KukeCoreInvocationResult {
    if (this == null) return KukeCoreInvocationResult(false, "KukeCore bridge returned null")
    val success = callBoolean("success") ?: callBoolean("isSuccess") ?: (this as? Boolean) ?: false
    val message = callString("message") ?: toString()
    return KukeCoreInvocationResult(success, message)
}

private fun Any.callBoolean(name: String): Boolean? = runCatching { javaClass.getMethod(name).invoke(this) as? Boolean }.getOrNull()

private fun Any.callString(name: String): String? = runCatching { javaClass.getMethod(name).invoke(this) as? String }.getOrNull()

private fun List<KukeCoreMailAttachment>.toKukeAttachments(): List<Any> {
    val attachmentClass = Class.forName("com.kuke.equipment.service.MailService\$Attachment")
    val typeClass = Class.forName("com.kuke.equipment.service.MailService\$AttachmentType")
    @Suppress("UNCHECKED_CAST")
    val valueOf = typeClass.getMethod("valueOf", String::class.java) as Method
    val constructor = attachmentClass.getConstructor(typeClass, String::class.java, Int::class.javaPrimitiveType)
    return map { attachment ->
        val enumName = attachment.type.trim().replace('-', '_').uppercase(Locale.ROOT)
        val type = runCatching { valueOf.invoke(null, enumName) }.getOrElse { valueOf.invoke(null, "VANILLA") }
        constructor.newInstance(type, attachment.id, attachment.amount.coerceAtLeast(1))
    }
}

@Entry("kuke_core_action", "Call a KukeCore reward or ability hook", Colors.RED, "fa6-solid:gift")
class KukeCoreActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The KukeCore hook to invoke.")
    val hook: KukeCoreHook = KukeCoreHook.GIVE_EXP,
    @Help("Long amount. Used by GIVE_EXP.")
    val amountLong: Long = 0L,
    @Help("Decimal amount. Used by GIVE_MONEY and SEND_MAIL money.")
    val amountDouble: Double = 0.0,
    @Help("Item type for GIVE_ITEM: vanilla, template-equipment/template, consumable.")
    val type: String = "vanilla",
    @Help("Reward/ability id: material id, template id, skill id, or dungeon id.")
    val targetId: String = "",
    @Help("Stack/count amount for item/equipment rewards.")
    val amount: Int = 1,
    @Help("Mail title for SEND_MAIL.")
    val title: String = "Typewriter Reward",
    @Help("Mail body for SEND_MAIL.")
    val body: String = "",
    @Help("Mail attachments for SEND_MAIL.")
    val attachments: List<KukeCoreMailAttachment> = emptyList(),
    @Help("Optional reward idempotency key. Defaults to player+entry+hook+target when blank.")
    val rewardKey: String = "",
    @Help("Optional request id used as the reward idempotency key when rewardKey is blank.")
    val requestId: String = "",
    @Help("Optional explicit idempotency key. Takes precedence over rewardKey and requestId.")
    val idempotencyKey: String = "",
    @Help("Optional trigger to run when KukeCore reports failure.")
    val failureTrigger: Ref<TriggerableEntry> = emptyRef(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val result = KukeCoreBridge.invoke(
            player,
            hook,
            KukeCorePayload(
                amountLong,
                amountDouble,
                type,
                targetId,
                amount,
                title,
                body,
                attachments,
                bridgeIdempotencyKey(),
            ),
        )
        if (!result.success) {
            failureTrigger.triggerFor(player, context())
        }
    }

    private fun bridgeIdempotencyKey(): String {
        return listOf(idempotencyKey, rewardKey, requestId).firstOrNull { it.isNotBlank() }
            ?: listOf(id, hook.name, type, targetId).joinToString(":")
    }
}
