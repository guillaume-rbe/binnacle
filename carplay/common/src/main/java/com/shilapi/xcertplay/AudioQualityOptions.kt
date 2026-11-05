package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AudioQualityLimit
import com.shilapi.xcertplay.airplay.AudioQualityProfiles
import com.shilapi.xcertplay.airplay.AudioQualitySettings
import com.shilapi.xcertplay.media.AudioQualityCapabilities

internal enum class QualityStream(val route: AudioQualityProfiles.Route, val key: String) {
    MUSIC(AudioQualityProfiles.Route.MUSIC, "music"),
    ASSISTANT(AudioQualityProfiles.Route.ASSISTANT, "assistant"),
    NAVIGATION(AudioQualityProfiles.Route.NAVIGATION, "navigation"),
    PHONE(AudioQualityProfiles.Route.PHONE, "phone"),
    MICROPHONE(AudioQualityProfiles.Route.MICROPHONE, "microphone"),
}

/** 每一路当前可选的声道与采样率：协议允许 ∩ 本机能力 ∩ 用户设置的上限。 */
internal class QualityOptions(private val byStream: Map<QualityStream, Map<Int, List<Int>>>) {
    // 预先算好，避免每次重组都过滤排序一遍。
    private val channelLists = byStream.mapValues { (_, byChannels) ->
        byChannels.filterValues { it.isNotEmpty() }.keys.sorted()
    }

    fun channels(stream: QualityStream): List<Int> = channelLists[stream].orEmpty()

    fun rates(stream: QualityStream, channels: Int): List<Int> =
        byStream[stream]?.get(channels).orEmpty()

    /** 用户设置是上限：在可用项里取不超过它的最大值。 */
    fun effective(stream: QualityStream, requested: AudioQualityLimit): AudioQualityLimit? {
        val channels = channels(stream).lastOrNull { it <= requested.channels } ?: return null
        val rate = rates(stream, channels).lastOrNull { it <= requested.sampleRate } ?: return null
        return AudioQualityLimit(rate, channels)
    }

    companion object {
        fun build(
            caps: AudioQualityCapabilities.Result?,
            audioQuality: AudioQualitySettings,
            wirelessEnabled: Boolean,
        ): QualityOptions {
            if (caps == null || caps.failure != null) return QualityOptions(emptyMap())
            val opus = audioQuality.opusEnabled
            return QualityOptions(
                QualityStream.entries.associateWith { stream ->
                    AudioQualityProfiles.channels(stream.route, wirelessEnabled, opus).associateWith { channels ->
                        AudioQualityProfiles.rates(stream.route, wirelessEnabled, opus)
                            .filter { it in machineRates(caps, stream.route, channels, opus, wirelessEnabled) }
                    }
                },
            )
        }

        /** 某一路某种声道数下，本机真正能处理的采样率。 */
        private fun machineRates(
            caps: AudioQualityCapabilities.Result,
            route: AudioQualityProfiles.Route,
            channels: Int,
            opus: Boolean,
            wirelessEnabled: Boolean,
        ): List<Int> = when {
            // 无线且关闭 Opus 时，麦克风上行走 ELD 编码，由编码器实测结果决定。
            route == AudioQualityProfiles.Route.MICROPHONE && wirelessEnabled && !opus -> caps.eldMicrophoneRates
            route == AudioQualityProfiles.Route.MICROPHONE && !wirelessEnabled -> caps.microphoneCaptureRates
            else -> caps.rates(AudioQualityProfiles.codec(route, wirelessEnabled, opus), channels)
        }
    }
}

internal fun AudioQualitySettings.limit(stream: QualityStream): AudioQualityLimit = when (stream) {
    QualityStream.MUSIC -> music
    QualityStream.ASSISTANT -> assistant
    QualityStream.NAVIGATION -> navigation
    QualityStream.PHONE -> phone
    QualityStream.MICROPHONE -> microphone
}

/**
 * 生成真正写进 /info 的上限。任何一路没有可用组合时退回该路的原始设置，
 * 而不是让整个配置构造失败：手机自己会再筛一遍。
 */
internal fun AudioQualitySettings.effective(options: QualityOptions): AudioQualitySettings {
    fun capped(stream: QualityStream): AudioQualityLimit =
        options.effective(stream, limit(stream)) ?: limit(stream)
    return copy(
        music = capped(QualityStream.MUSIC),
        assistant = capped(QualityStream.ASSISTANT),
        navigation = capped(QualityStream.NAVIGATION),
        phone = capped(QualityStream.PHONE),
        microphone = capped(QualityStream.MICROPHONE),
    )
}
