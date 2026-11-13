package com.example.audio

import com.example.model.PartyAudioSettings

object AudioDuckingHelper {
    const val CHAT_ACTIVE_HOLD_MS = 6000L

    fun computeTargetDuckMultiplier(
        audioSettings: PartyAudioSettings,
        isPartyChatActive: Boolean
    ): Float {
        if (!audioSettings.chatDuckingEnabled || !isPartyChatActive) return 1.0f
        val clampedReduction = audioSettings.duckingReductionPercent.coerceIn(15, 65)
        return (1.0f - (clampedReduction / 100f)).coerceIn(0.30f, 0.90f)
    }

    fun computeEffectiveVolume(
        baseVolume: Float,
        duckMultiplier: Float
    ): Float {
        return (baseVolume.coerceIn(0f, 1f) * duckMultiplier.coerceIn(0.30f, 1.0f)).coerceIn(0f, 1f)
    }
}
