package com.shilapi.xcertplay

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AudioQualitySettings
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioQualityCapabilities
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.vehicle.VehicleDataConfig
import com.shilapi.xcertplay.vehicle.source.VehicleDataEntries
import com.shilapi.xcertplay.vehicle.source.VehicleDataSource
import com.shilapi.xcertplay.vehicle.source.VehicleDataSourceRequest
import java.io.File

/** A display size in pixels, as an activity or an embedding client sees it. */
internal data class DisplaySize(val width: Int, val height: Int)

/**
 * The saved settings a CarPlay session starts from. Loaded from [AirPlayPersistence] at each
 * start, so a session can be started without the settings screen: the screen saves before it
 * closes, and a cancelled edit is never saved.
 */
internal data class CarPlaySessionSettings(
    val mainWebDisplay: CarPlayWebDisplayConfig,
    val clusterWebDisplay: CarPlayWebDisplayConfig,
    val displayScaleTenths: Int,
    val hevcEnabled: Boolean,
    val hevcSoftwareDecoderEnabled: Boolean,
    val advancedAudioChannelMapping: Boolean,
    val audioFocusEnabled: Boolean,
    val audioQuality: AudioQualitySettings,
    val mainMediaAudioBufferDurationMs: Int,
    val microphoneGainPercent: Int,
    val debugLogsEnabled: Boolean,
    val audioPacketCaptureEnabled: Boolean,
    val manufacturer: String,
    val model: String,
    val oemLabel: String,
    val fps: Int,
    val widthPhysicalMm: Int,
    val physicalSizeBasis: AirPlayPhysicalSizeBasis,
    val maximumDetectedWidthPixels: Int,
    val maximumDetectedHeightPixels: Int,
    val rightHandDrive: Boolean,
    val safeAreaDrawOutside: Boolean,
    val vehicleDataConfig: VehicleDataConfig,
    val vehicleProfileJson: String,
    val locationReportingEnabled: Boolean,
    val wirelessEnabled: Boolean,
    val wifiHighPerformanceEnabled: Boolean,
    val mfiTarget: MfiTarget,
    val mfiI2cPath: String,
    val remoteMfiServer: String,
    val remoteMfiToken: String,
    val localMfiCertificateUri: String,
    val localMfiPrivateKeyUri: String,
    val wirelessHotspotMode: WirelessHotspotMode,
    val manualHotspotSsid: String,
    val manualHotspotPassphrase: String,
    val manualHotspotBand: ManualHotspotBand,
    val manualHotspotChannel: Int,
    val manualHotspotSecurity: ManualHotspotSecurity,
) {
    val normalizedManufacturer: String
        get() = manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    val normalizedModel: String
        get() = model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    fun runtimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = normalizedModel,
            manufacturer = normalizedManufacturer,
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        wifiHighPerformanceEnabled = wifiHighPerformanceEnabled,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    fun viewAreas(context: Context, size: DisplaySize): CarPlayViewAreas? {
        if (mainWebDisplay.enabled) return null
        return CarPlayViewAreas.build(
            currentWidth = size.width,
            currentHeight = size.height,
            savedSizes = AirPlayPersistence.loadSafeAreaSizes(context),
            scaleTenths = displayScaleTenths,
        ) { width, height -> AirPlayPersistence.loadSafeAreaRect(context, width, height) }
    }

    fun physicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    fun airPlayConfig(
        context: Context,
        size: DisplaySize,
        areas: CarPlayViewAreas?,
        microphone: Boolean,
    ): AirPlayConfig {
        val physical = physicalSize(size)
        val display = if (areas != null) {
            // The display is the envelope of all areas; its physical size keeps the current
            // activity's pixel density.
            AirPlayDisplayConfig(
                widthPixels = areas.widthPixels,
                heightPixels = areas.heightPixels,
                widthPhysicalMm = Math.round(physical.widthMm * areas.activityWidthPixels.toDouble() / size.width).toInt(),
                heightPhysicalMm = Math.round(physical.heightMm * areas.activityHeightPixels.toDouble() / size.height).toInt(),
                fps = fps,
                safeAreaDrawOutside = safeAreaDrawOutside,
                viewAreas = areas.areas,
                initialViewArea = checkNotNull(areas.indexFor(size.width, size.height)),
            )
        } else {
            val baseDisplay = AirPlayDisplayConfig(
                widthPixels = size.width,
                heightPixels = size.height,
                widthPhysicalMm = physical.widthMm,
                heightPhysicalMm = physical.heightMm,
                fps = fps,
            )
            val scaledDisplay = CarPlayDisplayScale.apply(
                baseDisplay,
                displayScaleTenths,
            )
            mainWebDisplay.override(scaledDisplay.copy(
                safeArea = AirPlaySafeArea.toInsets(
                    mapping = AirPlayPersistence.loadSafeAreaRect(context, size.width, size.height),
                    activityWidthPixels = size.width,
                    activityHeightPixels = size.height,
                    displayWidthPixels = scaledDisplay.widthPixels,
                    displayHeightPixels = scaledDisplay.heightPixels,
                ),
                safeAreaDrawOutside = safeAreaDrawOutside,
            ))
        }
        val cluster = clusterWebDisplay.takeIf { it.enabled }?.let {
            it.override(AirPlayDisplayConfig(it.width, it.height, widthPhysicalMm = widthPhysicalMm, fps = fps,
                primaryInputDevice = 0))
        }
        // 用进程级缓存而不是设置页状态：是否按实测收窄，不能取决于这次有没有点进音频质量页。
        // 本进程还没探测过时为 null，保持协议默认声明。
        val caps = AudioQualityCapabilities.cached()
        return AirPlayConfig(
            deviceName = "xcertplay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            cluster = cluster,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphone,
            audioQuality = audioQuality.effective(QualityOptions.build(caps, audioQuality, wirelessEnabled)),
            audioSupportedFormats = caps?.allowedFormatBits,
            manufacturer = normalizedManufacturer,
            model = normalizedModel,
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon(context)),
        )
    }

    /** The source is handed to the long-lived controller; it only ever holds the app context. */
    fun vehicleDataSource(context: Context, diagnostic: Boolean = false): VehicleDataSource {
        val profile = com.shilapi.xcertplay.vehicle.profile.VehicleProfileParser.parse(vehicleProfileJson)
        val entries = InstalledVehicleDataEntries.all { profile }
        return VehicleDataEntries.select(entries, vehicleDataConfig.sourceId).create(
            VehicleDataSourceRequest(context.applicationContext, vehicleDataConfig, diagnostic),
        )
    }

    fun audioCaptureDirectory(context: Context): File? {
        if (!audioPacketCaptureEnabled) return null
        return File(context.filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    companion object {
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"

        fun load(context: Context): CarPlaySessionSettings {
            val (maxWidth, maxHeight) = AirPlayPersistence.loadMaximumDetectedDisplay(context)
            return CarPlaySessionSettings(
                mainWebDisplay = AirPlayPersistence.loadWebDisplay(context, CarPlaySession.SCREEN_TYPE_MAIN),
                clusterWebDisplay = AirPlayPersistence.loadWebDisplay(context, CarPlaySession.SCREEN_TYPE_ALT),
                displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(context),
                hevcEnabled = AirPlayPersistence.loadHevcEnabled(context),
                hevcSoftwareDecoderEnabled =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context),
                advancedAudioChannelMapping =
                    context.resources.getBoolean(R.bool.config_advanced_audio_channel_mapping) &&
                        AirPlayPersistence.loadAdvancedAudioChannelMapping(context),
                audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(context),
                audioQuality = AirPlayPersistence.loadAudioQuality(context),
                mainMediaAudioBufferDurationMs = AirPlayPersistence.loadMainMediaAudioBufferDurationMs(context),
                microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(context),
                debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(context),
                audioPacketCaptureEnabled = AirPlayPersistence.loadAudioPacketCaptureEnabled(context),
                manufacturer = AirPlayPersistence.loadManufacturer(context),
                model = AirPlayPersistence.loadModel(context),
                oemLabel = AirPlayPersistence.loadOemLabel(context),
                fps = AirPlayPersistence.loadFps(context),
                widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(context),
                physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(context),
                maximumDetectedWidthPixels = maxWidth,
                maximumDetectedHeightPixels = maxHeight,
                rightHandDrive = AirPlayPersistence.loadRightHandDrive(context),
                safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(context),
                vehicleDataConfig = AirPlayPersistence.loadVehicleDataConfig(context),
                vehicleProfileJson = VehicleProfileRepository(context).load().text,
                locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(context),
                wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(context),
                wifiHighPerformanceEnabled = AirPlayPersistence.loadWifiHighPerformanceEnabled(context),
                mfiTarget = AirPlayPersistence.loadMfiTarget(context),
                mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(context),
                remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(context),
                remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(context),
                localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(context),
                localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(context),
                wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(context),
                manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(context),
                manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
                manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(context),
                manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(context),
                manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
            )
        }

        fun loadAirPlayIcon(context: Context): AirPlayIcon {
            val customBytes = try {
                AirPlayPersistence.loadCustomAirPlayIconFile(context)?.readBytes()
            } catch (_: Exception) {
                null
            }
            if (customBytes != null) {
                decodeAirPlayIcon(customBytes)?.let { return it }
                AirPlayPersistence.clearCustomAirPlayIcon(context)
            }
            val packaged = context.resources.openRawResource(R.raw.placeholder_icon).use { it.readBytes() }
            return decodeAirPlayIcon(packaged)
                ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
        }

        private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth != bounds.outHeight
            ) {
                return null
            }
            return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
        }
    }
}
