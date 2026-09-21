package com.pixellog.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * AudioInputManager handles discovery, classification, dynamic selection, and hot-plugging
 * of audio input devices (built-in mics, external USB-C microphones, 3.5mm headsets, and Bluetooth).
 */
class AudioInputManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioInputManager"
    }

    data class MicDevice(
        val id: Int,
        val name: String,
        val type: Int,
        val typeLabel: String,
        val isExternal: Boolean,
        val deviceInfo: AudioDeviceInfo?
    ) {
        override fun toString(): String = "$name ($typeLabel)"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeAudioRecord: AudioRecord? = null
    var onDeviceListChanged: ((List<MicDevice>) -> Unit)? = null
    var onActiveDeviceChanged: ((MicDevice?) -> Unit)? = null

    // Routing Mode: null = AUTO (prefer external if available, fallback to internal)
    var selectedDevice: MicDevice? = null
        private set

    var isAutoRouting: Boolean = true
        private set

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val names = addedDevices?.joinToString { it.productName.toString() }
            Log.i(TAG, "Audio devices added: $names")
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val names = removedDevices?.joinToString { it.productName.toString() }
            Log.i(TAG, "Audio devices removed: $names")
            refreshDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        refreshDevices()
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    fun getAvailableInputDevices(): List<MicDevice> {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val list = mutableListOf<MicDevice>()

        for (dev in inputDevices) {
            val type = dev.type
            val isExternal: Boolean
            val typeLabel: String

            when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> {
                    typeLabel = "Internal"
                    isExternal = false
                }
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    typeLabel = "USB"
                    isExternal = true
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    typeLabel = "Wired 3.5mm"
                    isExternal = true
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                    typeLabel = "Bluetooth"
                    isExternal = true
                }
                else -> {
                    typeLabel = "Other"
                    isExternal = false
                }
            }

            val prodName = dev.productName.toString().takeIf { it.isNotBlank() }
                ?: when (type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio Device"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth Mic"
                    else -> "Audio Input $type"
                }

            list.add(MicDevice(dev.id, prodName, type, typeLabel, isExternal, dev))
        }

        return list
    }

    fun refreshDevices() {
        val devices = getAvailableInputDevices()
        onDeviceListChanged?.invoke(devices)

        if (isAutoRouting) {
            val autoDev = pickBestDevice(devices)
            applyRouting(autoDev)
        } else {
            val stillConnected = devices.any { it.id == selectedDevice?.id }
            if (!stillConnected) {
                Log.w(TAG, "Selected mic ${selectedDevice?.name} disconnected! Falling back to AUTO.")
                isAutoRouting = true
                val autoDev = pickBestDevice(devices)
                applyRouting(autoDev)
            } else {
                applyRouting(selectedDevice)
            }
        }
    }

    private fun pickBestDevice(devices: List<MicDevice>): MicDevice? {
        // Priority: USB > 3.5mm Wired > Bluetooth > Built-in Mic
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: devices.firstOrNull()
    }

    fun setAutoRouting() {
        isAutoRouting = true
        selectedDevice = null
        val best = pickBestDevice(getAvailableInputDevices())
        applyRouting(best)
    }

    fun setSpecificDevice(device: MicDevice) {
        isAutoRouting = false
        selectedDevice = device
        applyRouting(device)
    }

    /**
     * Cycles to the next available input option:
     * AUTO -> Built-in -> External 1 -> External 2 -> ... -> AUTO
     */
    fun cycleNextDevice(): MicDevice? {
        val devices = getAvailableInputDevices()
        if (devices.isEmpty()) {
            setAutoRouting()
            return null
        }

        if (isAutoRouting) {
            // First explicit option: Built-in mic
            val builtIn = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: devices.first()
            setSpecificDevice(builtIn)
            return builtIn
        } else {
            val currentIndex = devices.indexOfFirst { it.id == selectedDevice?.id }
            if (currentIndex == -1 || currentIndex >= devices.size - 1) {
                // Cycle back to AUTO
                setAutoRouting()
                return selectedDevice
            } else {
                val nextDev = devices[currentIndex + 1]
                setSpecificDevice(nextDev)
                return nextDev
            }
        }
    }

    fun bindAudioRecord(audioRecord: AudioRecord) {
        activeAudioRecord = audioRecord
        val dev = if (isAutoRouting) pickBestDevice(getAvailableInputDevices()) else selectedDevice
        if (dev?.deviceInfo != null) {
            val ok = audioRecord.setPreferredDevice(dev.deviceInfo)
            Log.i(TAG, "Applied preferred audio device ${dev.name} to AudioRecord: success=$ok")
        }
    }

    fun unbindAudioRecord() {
        activeAudioRecord = null
    }

    private fun applyRouting(device: MicDevice?) {
        selectedDevice = device
        activeAudioRecord?.let { record ->
            if (device?.deviceInfo != null) {
                val ok = record.setPreferredDevice(device.deviceInfo)
                Log.i(TAG, "Dynamically routed AudioRecord to ${device.name}: success=$ok")
            } else {
                record.setPreferredDevice(null)
            }
        }
        onActiveDeviceChanged?.invoke(device)
    }

    fun getActiveDeviceLabel(): String {
        val dev = selectedDevice ?: pickBestDevice(getAvailableInputDevices())
        val name = when {
            dev == null -> "NONE"
            dev.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "INTERNAL"
            dev.isExternal -> dev.typeLabel.uppercase()
            else -> dev.typeLabel.uppercase()
        }

        return if (isAutoRouting) {
            "MIC: AUTO ($name)"
        } else {
            "MIC: $name"
        }
    }
}
