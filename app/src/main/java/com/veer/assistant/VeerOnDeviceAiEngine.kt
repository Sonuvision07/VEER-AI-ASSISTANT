package com.veer.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns the on-device language model lifecycle.
 *
 * The model file is stored in app-private storage. No API key, remote inference
 * endpoint, or user account is used for generation.
 */
class VeerOnDeviceAiEngine private constructor(
    private val modelFile: File
) : AutoCloseable {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val generationMutex = Mutex()

    val isModelInstalled: Boolean
        get() = modelFile.isFile && modelFile.length() > MIN_MODEL_BYTES

    suspend fun initialize() {
        check(isModelInstalled) {
            "VEER ka offline AI model abhi install nahi hai."
        }
        close()
        val loadedEngine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                cacheDir = modelFile.parentFile?.resolve("cache")?.absolutePath
            )
        )
        try {
            loadedEngine.initialize()
            engine = loadedEngine
            conversation = loadedEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are VEER, a helpful personal assistant. Reply naturally in Hindi or Hinglish matching the user. Be accurate, practical and concise. Never claim you performed an action unless the app confirms it."
                    )
                )
            )
        } catch (error: Throwable) {
            loadedEngine.close()
            throw error
        }
    }

    suspend fun generateReply(prompt: String): String = generationMutex.withLock {
        require(prompt.isNotBlank()) { "Message khaali nahi ho sakta." }
        val activeConversation = checkNotNull(conversation) {
            "VEER offline AI model initialize nahi hua."
        }
        val answer = StringBuilder()
        activeConversation.sendMessageAsync(prompt).collect { message ->
            answer.append(message.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text })
        }
        answer.toString().trim()
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    companion object {
        private const val MIN_MODEL_BYTES = 10L * 1024L * 1024L
        private const val MODEL_FILE_NAME = "veer-local-model.litertlm"

        fun modelFile(context: Context): File =
            File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE_NAME)

        fun create(context: Context): VeerOnDeviceAiEngine =
            VeerOnDeviceAiEngine(modelFile(context))
    }
}
