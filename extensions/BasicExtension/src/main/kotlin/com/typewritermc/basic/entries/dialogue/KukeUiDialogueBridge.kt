package com.typewritermc.basic.entries.dialogue

import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.dialogue.DialogueTrigger
import com.typewritermc.engine.paper.entry.entries.DialogueEntry
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.plugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class KukeUiDialogueOption(
    val index: Int,
    val text: String,
    val selected: Boolean = false,
)

data class KukeUiDialogueState(
    val sessionId: String,
    val kind: String,
    val speakerName: String,
    val text: String,
    val typingMillis: Int,
    val waitMillis: Int = 0,
    val allowSkip: Boolean = true,
    val canFinish: Boolean = true,
    val showAvatar: Boolean = false,
    val avatarKind: String = "none",
    val avatarUrl: String = "",
    val avatarTexture: String = "",
    val avatarSignature: String = "",
    val selectedIndex: Int = 0,
    val options: List<KukeUiDialogueOption> = emptyList(),
    val entryId: String = "",
    val entryName: String = "",
    val speakerId: String = "",
    val speakerType: String = "",
    val soundKey: String = "",
    val inputMode: String = "none",
    val inputHint: String = "",
    val inputError: String = "",
)

object KukeUiDialogueBridge : Listener {
    private const val NAMESPACE = "typewriter"
    private const val KUKEUI_FACADE = "kuke.kukeui.KukeUI"
    private const val KUKEUI_PACKET_EVENT = "kuke.kukeui.event.UIPacketEvent"
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private var registered = false

    fun ensureRegistered() {
        if (registered) return
        registered = true
        Bukkit.getPluginManager().registerEvents(this, plugin)
        runCatching {
            val kukeUi = Class.forName(KUKEUI_FACADE)
            kukeUi.getMethod("register", String::class.java, org.bukkit.plugin.Plugin::class.java)
                .invoke(null, NAMESPACE, plugin)
        }
    }

    fun hasMod(player: Player): Boolean {
        ensureRegistered()
        return runCatching {
            val kukeUi = Class.forName(KUKEUI_FACADE)
            kukeUi.getMethod("hasMod", Player::class.java).invoke(null, player) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun update(
        player: Player,
        state: KukeUiDialogueState,
        onContinue: () -> Unit,
        onSelect: (Int) -> Unit = {},
        onInput: (String) -> Unit = {},
    ) {
        ensureRegistered()
        if (!hasMod(player)) return
        sessions[player.uniqueId] = Session(state.sessionId, onContinue, onSelect, onInput)
        send(player, "dialogue_update") { output -> output.writeDialogueState(state) }
    }

    fun clear(player: Player, sessionId: String) {
        if (!hasMod(player)) return
        sessions.remove(player.uniqueId)
        send(player, "dialogue_clear") { output -> output.writeUTF(sessionId) }
    }

    fun cameraStart(player: Player, cinematicId: String = "", cinematicName: String = "", totalFrames: Int = 0, segmentCount: Int = 0) {
        if (!hasMod(player)) return
        send(player, "camera_start") { output ->
            output.writeUTF(cinematicId)
            output.writeUTF(cinematicName)
            output.writeInt(totalFrames.coerceAtLeast(0))
            output.writeInt(segmentCount.coerceAtLeast(0))
        }
    }

    fun cameraFrame(player: Player, cinematicId: String = "", frame: Int = 0, totalFrames: Int = 0, segmentIndex: Int = -1) {
        if (!hasMod(player)) return
        send(player, "camera_frame") { output ->
            output.writeUTF(cinematicId)
            output.writeInt(frame.coerceAtLeast(0))
            output.writeInt(totalFrames.coerceAtLeast(0))
            output.writeInt(segmentIndex)
        }
    }

    fun cameraStop(player: Player, cinematicId: String = "") {
        if (!hasMod(player)) return
        send(player, "camera_stop") { output -> output.writeUTF(cinematicId) }
    }

    fun baseState(
        player: Player,
        entry: DialogueEntry,
        kind: String,
        speakerName: String,
        text: String,
        typingMillis: Int,
        waitMillis: Int = 0,
        allowSkip: Boolean = true,
        canFinish: Boolean = true,
        inputMode: String = "none",
        inputHint: String = "",
        inputError: String = "",
        selectedIndex: Int = 0,
        options: List<KukeUiDialogueOption> = emptyList(),
    ): KukeUiDialogueState {
        val speaker = entry.speaker.get()
        val avatar = resolveAvatar(player, entry, speaker)
        return KukeUiDialogueState(
            sessionId = kukeUiDialogueSessionId(player, entry.id),
            kind = kind,
            speakerName = speakerName,
            text = text,
            typingMillis = typingMillis,
            waitMillis = waitMillis,
            allowSkip = allowSkip,
            canFinish = canFinish,
            showAvatar = true,
            avatarKind = avatar.kind,
            avatarUrl = avatar.url,
            avatarTexture = avatar.texture,
            avatarSignature = avatar.signature,
            selectedIndex = selectedIndex,
            options = options,
            entryId = entry.id,
            entryName = entry.name,
            speakerId = speaker?.javaClass?.methods?.firstOrNull { it.name == "getId" }?.invoke(speaker) as? String ?: "",
            speakerType = speaker?.javaClass?.simpleName ?: "",
            soundKey = resolveSoundKey(player, entry),
            inputMode = inputMode,
            inputHint = inputHint,
            inputError = inputError,
        )
    }

    @EventHandler
    fun onPacket(event: org.bukkit.event.Event) {
        if (!isKukeUiPacketEvent(event)) return
        val namespace = event.callString("getNamespace") ?: return
        if (namespace != NAMESPACE) return
        val player = event.callPlayer("getPlayer") ?: return
        val session = sessions[player.uniqueId] ?: return
        val action = event.callString("getAction") ?: return
        val payload = event.callByteArray("getPayload") ?: return
        val input = DataInputStream(ByteArrayInputStream(payload))
        when (action) {
            "dialogue_continue" -> {
                val sessionId = input.readUTF()
                if (sessionId == session.sessionId) session.onContinue()
            }

            "dialogue_select" -> {
                val sessionId = input.readUTF()
                val selectedIndex = input.readInt()
                if (sessionId == session.sessionId) session.onSelect(selectedIndex)
            }

            "dialogue_input" -> {
                val sessionId = input.readUTF()
                val value = input.readUTF()
                if (sessionId == session.sessionId) session.onInput(value)
            }

            "dialogue_close" -> {
                val sessionId = input.readUTF()
                if (sessionId == session.sessionId) {
                    sessions.remove(player.uniqueId)
                    DialogueTrigger.FORCE_NEXT.triggerFor(player, player.interactionContext ?: context())
                }
            }
        }
    }

    private fun send(player: Player, action: String, writer: (DataOutputStream) -> Unit) {
        runCatching {
            val kukeUi = Class.forName(KUKEUI_FACADE)
            val sendMethod = kukeUi.methods.firstOrNull { method ->
                method.name == "send" &&
                    method.parameterTypes.size == 4 &&
                    method.parameterTypes[0] == Player::class.java &&
                    method.parameterTypes[1] == String::class.java &&
                    method.parameterTypes[2] == String::class.java
            } ?: return@runCatching
            val payloadWriterType = sendMethod.parameterTypes[3]
            val payloadWriter = java.lang.reflect.Proxy.newProxyInstance(
                payloadWriterType.classLoader,
                arrayOf(payloadWriterType),
            ) { _, method, args ->
                if (method.name == "write") {
                    writer(args?.firstOrNull() as DataOutputStream)
                }
                null
            }
            sendMethod.invoke(null, player, NAMESPACE, action, payloadWriter)
        }.recoverCatching {
            val kukeUiPlugin = Bukkit.getPluginManager().getPlugin("KukeUI") ?: return@recoverCatching
            val sendRaw = kukeUiPlugin.javaClass.methods.firstOrNull { method ->
                method.name == "sendRaw" && method.parameterTypes.contentEquals(
                    arrayOf(Player::class.java, String::class.java, String::class.java, ByteArray::class.java),
                )
            } ?: return@recoverCatching
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use(writer)
            sendRaw.invoke(kukeUiPlugin, player, NAMESPACE, action, buffer.toByteArray())
        }
    }

    private fun resolveSoundKey(player: Player, entry: DialogueEntry): String = runCatching {
        val sound = entry.speaker.get()?.sound?.get(player, player.interactionContext ?: context()) ?: return@runCatching ""
        val soundId = sound.javaClass.methods.firstOrNull { it.name == "getSoundId" }?.invoke(sound) ?: return@runCatching ""
        val key = soundId.javaClass.methods.firstOrNull { it.name == "getNamespacedKey" }?.invoke(soundId) ?: return@runCatching ""
        key.toString()
    }.getOrDefault("")

    private fun resolveAvatar(player: Player, entry: DialogueEntry, speaker: Any?): AvatarMeta {
        val skin = resolveSkinFrom(speaker, player)
        if (skin != null && skin.texture.isNotBlank()) return AvatarMeta("skin_texture", texture = skin.texture, signature = skin.signature)
        return AvatarMeta("initial")
    }

    private fun resolveSkinFrom(target: Any?, player: Player): SkinMeta? = runCatching {
        if (target == null) return@runCatching null
        val getSkin = target.javaClass.methods.firstOrNull { it.name == "getSkin" && it.parameterTypes.isEmpty() } ?: return@runCatching null
        val skinVar = getSkin.invoke(target) ?: return@runCatching null
        val get = skinVar.javaClass.methods.firstOrNull { it.name == "get" && it.parameterTypes.size in 1..2 } ?: return@runCatching null
        val skin = if (get.parameterTypes.size == 2) get.invoke(skinVar, player, player.interactionContext ?: context()) else get.invoke(skinVar, player)
        val texture = skin?.javaClass?.methods?.firstOrNull { it.name == "getTexture" }?.invoke(skin) as? String ?: ""
        val signature = skin?.javaClass?.methods?.firstOrNull { it.name == "getSignature" }?.invoke(skin) as? String ?: ""
        SkinMeta(texture, signature)
    }.getOrNull()

    private fun isKukeUiPacketEvent(event: org.bukkit.event.Event): Boolean =
        runCatching { Class.forName(KUKEUI_PACKET_EVENT).isInstance(event) }.getOrDefault(false)

    private fun DataOutputStream.writeDialogueState(state: KukeUiDialogueState) {
        writeUTF(state.sessionId)
        writeUTF(state.kind)
        writeUTF(state.speakerName)
        writeUTF(state.text)
        writeInt(state.typingMillis)
        writeInt(state.waitMillis)
        writeBoolean(state.allowSkip)
        writeBoolean(state.canFinish)
        writeBoolean(state.showAvatar)
        writeUTF(state.avatarKind)
        writeUTF(state.avatarUrl)
        writeUTF(state.avatarTexture)
        writeUTF(state.avatarSignature)
        writeInt(state.selectedIndex)
        writeInt(state.options.size)
        state.options.forEach { option ->
            writeInt(option.index)
            writeUTF(option.text)
            writeBoolean(option.selected)
        }
        writeUTF(state.entryId)
        writeUTF(state.entryName)
        writeUTF(state.speakerId)
        writeUTF(state.speakerType)
        writeUTF(state.soundKey)
        writeUTF(state.inputMode)
        writeUTF(state.inputHint)
        writeUTF(state.inputError)
    }

    private fun org.bukkit.event.Event.callString(method: String): String? = call(method) as? String
    private fun org.bukkit.event.Event.callPlayer(method: String): Player? = call(method) as? Player
    private fun org.bukkit.event.Event.callByteArray(method: String): ByteArray? = call(method) as? ByteArray
    private fun org.bukkit.event.Event.call(method: String): Any? =
        runCatching { javaClass.getMethod(method).invoke(this) }.getOrNull()

    private data class Session(
        val sessionId: String,
        val onContinue: () -> Unit,
        val onSelect: (Int) -> Unit,
        val onInput: (String) -> Unit,
    )

    private data class AvatarMeta(
        val kind: String,
        val url: String = "",
        val texture: String = "",
        val signature: String = "",
    )

    private data class SkinMeta(val texture: String, val signature: String)
}

fun kukeUiDialogueSessionId(player: Player, entryId: String): String = "${player.uniqueId}:$entryId"
fun Duration.toKukeUiMillis(): Int = toMillis().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
