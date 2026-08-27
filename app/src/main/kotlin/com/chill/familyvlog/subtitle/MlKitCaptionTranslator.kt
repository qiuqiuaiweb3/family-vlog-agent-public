package com.chill.familyvlog.subtitle

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class MlKitCaptionTranslatorFactory : CaptionTranslatorFactory {
    override fun create(): CaptionTranslatorSession = MlKitCaptionTranslatorSession()
}

private class MlKitCaptionTranslatorSession : CaptionTranslatorSession {
    private val modelManager = RemoteModelManager.getInstance()
    private val chineseModel = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
    private val chineseToEnglish = createTranslator(
        TranslateLanguage.CHINESE,
        TranslateLanguage.ENGLISH,
    )
    private val englishToChinese: Translator = try {
        createTranslator(TranslateLanguage.ENGLISH, TranslateLanguage.CHINESE)
    } catch (failure: Throwable) {
        chineseToEnglish.close()
        throw failure
    }
    private var closed = false

    override suspend fun prepare(onDownloadRequired: () -> Unit) {
        check(!closed)
        if (!modelManager.isModelDownloaded(chineseModel).await()) {
            onDownloadRequired()
            modelManager.download(
                chineseModel,
                DownloadConditions.Builder().requireWifi().build(),
            ).await()
        }
    }

    override suspend fun translate(sourceLanguage: SubtitleLanguage, text: String): String {
        check(!closed && text.isNotBlank())
        val translator = when (sourceLanguage) {
            SubtitleLanguage.CHINESE -> chineseToEnglish
            SubtitleLanguage.ENGLISH -> englishToChinese
        }
        return translator.translate(text).await()
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            chineseToEnglish.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            englishToChinese.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }
}

private fun createTranslator(source: String, target: String): Translator = Translation.getClient(
    TranslatorOptions.Builder()
        .setSourceLanguage(source)
        .setTargetLanguage(target)
        .build(),
)
