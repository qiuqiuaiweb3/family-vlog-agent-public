package com.chill.familyvlog.ai

import android.content.res.AssetManager

interface PromptSource {
    fun read(name: String): String
}

class AssetManagerPromptSource(private val assets: AssetManager) : PromptSource {
    override fun read(name: String): String = assets.open(name).bufferedReader().use { it.readText() }
}

class PromptRepository(private val source: PromptSource) {
    fun understandingSystemPrompt(): String = source.read("video-understanding-system.md")

    fun editorSystemPrompt(): String = source.read("vlog-editor-system.md")
}
