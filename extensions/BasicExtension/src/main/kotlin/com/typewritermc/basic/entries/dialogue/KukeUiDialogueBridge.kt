package com.typewritermc.basic.entries.dialogue

import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.dialogue.DialogueTrigger
import com.typewritermc.engine.paper.entry.entries.DialogueEntry
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.plugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_OPTIONS = 32
private const val MAX_UTF_BYTES = 60_000

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
    private var kukeUiClass: Class<*>? = null
    private var hasModMethod: Method? = null
    private var sendMethod: Method? = null
    private var sendRawMethod: Method? = null

    @Synchronized
    fun ensureRegistered() {
        if (registered) return
        registered = true
        val kukeUi = loadKukeUiClass() ?: return
        runCatching {
            kukeUi.getMethod("register", String::class.java, org.bukkit.plugin.Plugin::class.java)
                .invoke(null, NAMESPACE, plugin)
        }
        registerPacketListener()
    }

    fun hasMod(player: Player): Boolean {
        ensureRegistered()
        return runCatching {
            val method = hasModMethod ?: loadKukeUiClass()?.getMethod("hasMod", Player::class.java)?.also {
                hasModMethod = it
            } ?: return false
            method.invoke(null, player) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun update(
        player: Player,
        state: KukeUiDialogueState,
        onContinue: () -> Unit,
        onSelect: (Int) -> Unit = {},
        onInput: (String) -> Unit = {},
    ): Boolean {
        ensureRegistered()
        if (!hasMod(player)) return false
        val sent = send(player, "dialogue_update") { output -> output.writeDialogueState(state) }
        if (sent) {
            sessions[player.uniqueId] = Session(state.sessionId, onContinue, onSelect, onInput)
        }
        return sent
    }

    fun clear(player: Player, sessionId: String) {
        sessions.remove(player.uniqueId)
        if (!hasMod(player)) return
        send(player, "dialogue_clear") { output -> output.writeSafeUTF(sessionId) }
    }

    fun cameraStart(
        player: Player,
        cinematicId: String = "",
        cinematicName: String = "",
        totalFrames: Int = 0,
        segmentCount: Int = 0,
    ) {
        if (!hasMod(player)) return
        send(player, "camera_start") { output ->
            output.writeSafeUTF(cinematicId)
            output.writeSafeUTF(cinematicName)
            output.writeInt(totalFrames.coerceAtLeast(0))
            output.writeInt(segmentCount.coerceAtLeast(0))
        }
    }

    fun cameraFrame(player: Player, cinematicId: String = "", frame: Int = 0, totalFrames: Int = 0, segmentIndex: Int = -1) {
        if (!hasMod(player)) return
        send(player, "camera_frame") { output ->
            output.writeSafeUTF(cinematicId)
            output.writeInt(frame.coerceAtLeast(0))
            output.writeInt(totalFrames.coerceAtLeast(0))
            output.writeInt(segmentIndex)
        }
    }

    fun cameraStop(player: Player, cinematicId: String = "") {
        if (!hasMod(player)) return
        send(player, "camera_stop") { output -> output.writeSafeUTF(cinematicId) }
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
            showAvatar = speakerName.isNotBlank(),
            avatarKind = avatar.kind,
            avatarUrl = avatar.url,
            avatarTexture = avatar.texture,
            avatarSignature = avatar.signature,
            selectedIndex = selectedIndex,
            options = options.take(MAX_OPTIONS),
            entryId = entry.id,
            entryName = entry.name,
            speakerId = resolveSpeakerId(speaker),
            speakerType = speaker?.javaClass?.simpleName ?: "",
            soundKey = resolveSoundKey(player, entry),
            inputMode = inputMode,
            inputHint = inputHint,
            inputError = inputError,
        )
    }

    private fun registerPacketListener() {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val eventClass = loadKukeUiEventClass() as? Class<out Event> ?: return@runCatching
            Bukkit.getPluginManager().registerEvent(
                eventClass,
                this,
                EventPriority.NORMAL,
                { _, event -> handlePacket(event) },
                plugin,
                false,
            )
        }
    }

    private fun handlePacket(event: Event) {
        if (!isKukeUiPacketEvent(event)) return
        val namespace = event.callString("getNamespace") ?: return
        if (namespace != NAMESPACE) return
        val player = event.callPlayer("getPlayer") ?: return
        val session = sessions[player.uniqueId] ?: return
        val action = event.callString("getAction") ?: return
        val payload = event.callByteArray("getPayload") ?: return
        runOnMainThread {
            runCatching {
                handlePacketPayload(player, session, action, payload)
            }
        }
    }

    private fun handlePacketPayload(player: Player, session: Session, action: String, payload: ByteArray) {
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

    private fun send(player: Player, action: String, writer: (DataOutputStream) -> Unit): Boolean =
        runCatching {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use(writer)
            val payload = buffer.toByteArray()
            sendViaFacade(player, action, payload) || sendViaPlugin(player, action, payload)
        }.getOrDefault(false)

    private fun sendViaFacade(player: Player, action: String, payload: ByteArray): Boolean = runCatching {
        val method = sendMethod ?: loadKukeUiClass()?.methods?.firstOrNull { method ->
            method.name == "send" &&
                method.parameterTypes.size == 4 &&
                method.parameterTypes[0] == Player::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == String::class.java &&
                method.parameterTypes[3].isInterface
        }?.also { sendMethod = it } ?: return false
        val payloadWriterType = method.parameterTypes[3]
        var wrote = false
        val payloadWriter = java.lang.reflect.Proxy.newProxyInstance(
            payloadWriterType.classLoader,
            arrayOf(payloadWriterType),
        ) { _, proxyMethod, args ->
            if ((proxyMethod.name == "write" || proxyMethod.name == "accept") && args?.firstOrNull() is DataOutputStream) {
                (args.first() as DataOutputStream).write(payload)
                wrote = true
            }
            null
        }
        method.invoke(null, player, NAMESPACE, action, payloadWriter)
        wrote
    }.getOrDefault(false)

    private fun sendViaPlugin(player: Player, action: String, payload: ByteArray): Boolean = runCatching {
        val kukeUiPlugin = Bukkit.getPluginManager().getPlugin("KukeUI") ?: return false
        val method = sendRawMethod ?: kukeUiPlugin.javaClass.methods.firstOrNull { method ->
            method.name == "sendRaw" && method.parameterTypes.contentEquals(
                arrayOf(Player::class.java, String::class.java, String::class.java, ByteArray::class.java),
            )
        }?.also { sendRawMethod = it } ?: return false
        method.invoke(kukeUiPlugin, player, NAMESPACE, action, payload)
        true
    }.getOrDefault(false)

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

    private fun resolveSpeakerId(speaker: Any?): String = runCatching {
        speaker?.javaClass?.methods?.firstOrNull { it.name == "getId" && it.parameterTypes.isEmpty() }
            ?.invoke(speaker) as? String ?: ""
    }.getOrDefault("")

    private fun loadKukeUiClass(): Class<*>? = kukeUiClass ?: runCatching {
        val loader = Bukkit.getPluginManager().getPlugin("KukeUI")?.javaClass?.classLoader ?: javaClass.classLoader
        Class.forName(KUKEUI_FACADE, false, loader)
    }.getOrNull()?.also { kukeUiClass = it }

    private fun loadKukeUiEventClass(): Class<*>? = runCatching {
        val loader = Bukkit.getPluginManager().getPlugin("KukeUI")?.javaClass?.classLoader ?: javaClass.classLoader
        Class.forName(KUKEUI_PACKET_EVENT, false, loader)
    }.getOrNull()

    private fun isKukeUiPacketEvent(event: Event): Boolean =
        runCatching { loadKukeUiEventClass()?.isInstance(event) == true }.getOrDefault(false)

    private fun runOnMainThread(task: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            task()
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable(task))
        }
    }

    private fun DataOutputStream.writeDialogueState(state: KukeUiDialogueState) {
        writeSafeUTF(state.sessionId)
        writeSafeUTF(state.kind)
        writeSafeUTF(state.speakerName)
        writeSafeUTF(state.text)
        writeInt(state.typingMillis)
        writeInt(state.waitMillis)
        writeBoolean(state.allowSkip)
        writeBoolean(state.canFinish)
        writeBoolean(state.showAvatar)
        writeSafeUTF(state.avatarKind)
        writeSafeUTF(state.avatarUrl)
        writeSafeUTF(state.avatarTexture)
        writeSafeUTF(state.avatarSignature)
        writeInt(state.selectedIndex)
        writeInt(state.options.size.coerceIn(0, MAX_OPTIONS))
        state.options.take(MAX_OPTIONS).forEach { option ->
            writeInt(option.index)
            writeSafeUTF(option.text)
            writeBoolean(option.selected)
        }
        writeSafeUTF(state.entryId)
        writeSafeUTF(state.entryName)
        writeSafeUTF(state.speakerId)
        writeSafeUTF(state.speakerType)
        writeSafeUTF(state.soundKey)
        writeSafeUTF(state.inputMode)
        writeSafeUTF(state.inputHint)
        writeSafeUTF(state.inputError)
    }

    private fun DataOutputStream.writeSafeUTF(value: String) {
        var text = value
        while (text.toByteArray(Charsets.UTF_8).size > MAX_UTF_BYTES) {
            text = text.dropLast((text.length / 4).coerceAtLeast(1))
        }
        writeUTF(text)
    }

    private fun Event.callString(method: String): String? = call(method) as? String
    private fun Event.callPlayer(method: String): Player? = call(method) as? Player
    private fun Event.callByteArray(method: String): ByteArray? = call(method) as? ByteArray
    private fun Event.call(method: String): Any? =
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
