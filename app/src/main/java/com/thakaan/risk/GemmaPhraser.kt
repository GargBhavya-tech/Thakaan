package com.thakaan.risk

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.thakaan.inference.ModelFiles

/**
 * Gemma 4 E2B (LiteRT-LM, GPU) as the optional [LlmPhraser] (#57). It only re-words the template line the risk engine already
 * produced; [Explainer.validate] then rejects anything that changes the action, the minutes, adds numbers or reassures.
 * Loading takes several seconds, so [load] must be called off the main thread. Any failure leaves the template in place.
 */
class GemmaPhraser(private val ctx: Context) : LlmPhraser {
    @Volatile private var engine: Engine? = null
    @Volatile var status: String = "not loaded"; private set

    val loaded: Boolean get() = engine != null

    /** Loads the engine from the pushed model file. Returns a one-line status for Diagnostics. */
    @Synchronized fun load(): String {
        if (engine != null) return status
        if (!ModelFiles.present(ctx, ModelFiles.GEMMA)) { status = "model file not on this phone"; return status }
        return try {
            val e = Engine(EngineConfig(modelPath = ModelFiles.file(ctx, ModelFiles.GEMMA).absolutePath, backend = Backend.GPU(), cacheDir = ctx.cacheDir.absolutePath))
            e.initialize()
            engine = e
            status = "loaded on GPU"
            status
        } catch (t: Throwable) {
            status = "failed: ${t.javaClass.simpleName}: ${t.message}"
            status
        }
    }

    @Synchronized fun close() { try { engine?.close() } catch (_: Throwable) {}; engine = null; status = "closed" }

    override fun phrase(templateLine: String, lang: Lang, level: Level, action: AlertAction): String? {
        val e = engine ?: return null
        val language = when (lang) { Lang.EN -> "English"; Lang.HI -> "Hindi"; Lang.KN -> "Kannada"; Lang.TA -> "Tamil"; Lang.TE -> "Telugu" }
        val prompt = "Re-word this safety message for an outdoor delivery worker in simple, kind $language. " +
            "Keep the same action and keep every number exactly. Do not add numbers, do not reassure, do not say it is safe or fine. " +
            "One or two short sentences. Reply with the message only.\n\nMessage: $templateLine"
        return try {
            e.createConversation().use { c -> c.sendMessage(prompt).toString() }
        } catch (_: Throwable) { null }
    }
}
