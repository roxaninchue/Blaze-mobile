package com.blaze.assistant.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.blaze.assistant.device.CameraZoom

/**
 * v0.45.28: the phone's back cameras as Camera2 describes them (for [CameraZoom.pickBack] / [CameraZoom.ultraWide]),
 * and a selector for one camera id. Anything that fails here leaves Blaze Camera on CameraX's default back camera.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
object CameraLenses {
    private fun idOf(info: CameraInfo): String? = try { Camera2CameraInfo.from(info).cameraId } catch (_: Throwable) { null }

    private fun isBack(info: CameraInfo): Boolean = try {
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } catch (_: Throwable) { false }

    private fun lens(info: CameraInfo): CameraZoom.Lens? {
        val c2 = try { Camera2CameraInfo.from(info) } catch (_: Throwable) { return null }
        val id = c2.cameraId
        val caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val logical = Build.VERSION.SDK_INT >= 28 && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val range = if (Build.VERSION.SDK_INT >= 30) c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val maxDigital = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
        val sensorW = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
        return CameraZoom.Lens(id, logical, range?.lower ?: 1f, range?.upper ?: maxDigital, if (sensorW > 0f) focal / sensorW else 0f)
    }

    /** Every back camera CameraX can open, in CameraX's own order (the first is its default). */
    fun backLenses(ctx: Context): List<CameraZoom.Lens> {
        val provider = ProcessCameraProvider.getInstance(ctx).get()
        return provider.availableCameraInfos.filter { isBack(it) }.mapNotNull { info ->
            try { lens(info) } catch (_: Throwable) { null }
        }
    }

    /** CameraX's default back camera among [lenses] (what DEFAULT_BACK_CAMERA opens). */
    fun defaultBack(ctx: Context, lenses: List<CameraZoom.Lens>): CameraZoom.Lens? = try {
        val provider = ProcessCameraProvider.getInstance(ctx).get()
        val first = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull()?.let { idOf(it) }
        lenses.firstOrNull { it.id == first } ?: lenses.firstOrNull()
    } catch (_: Throwable) { lenses.firstOrNull() }

    /** A back-camera selector for camera [id]; when that id isn't there it falls back to every back camera (the default). */
    fun selector(id: String): CameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter { infos -> infos.filter { idOf(it) == id }.ifEmpty { infos } }
        .build()
}
