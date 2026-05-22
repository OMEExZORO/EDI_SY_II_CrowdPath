package com.crowdpath.app.ui.mapping

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * Checks ARCore availability and returns a Session if the device supports it.
 * Returns null — with a human-readable reason — if ARCore is unavailable.
 */
sealed class ArSessionResult {
    data class Available(val session: Session) : ArSessionResult()
    data class Unavailable(val reason: ArUnavailableReason) : ArSessionResult()
}

enum class ArUnavailableReason {
    /** Device hardware is not on Google's ARCore whitelist */
    UNSUPPORTED_DEVICE,
    /** ARCore SDK not installed (user can install from Play Store) */
    NOT_INSTALLED,
    /** Device too old / SDK version too low */
    SDK_TOO_OLD,
    /** App doesn't have CAMERA permission */
    NO_CAMERA_PERMISSION,
    /** Unknown error */
    UNKNOWN
}

private const val TAG = "ArSessionFactory"

fun buildArSession(context: Context): ArSessionResult {
    // 1. Check availability without triggering an install prompt
    return try {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        when {
            availability.isTransient -> {
                // Still checking — default to unavailable for now
                ArSessionResult.Unavailable(ArUnavailableReason.UNKNOWN)
            }
            availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                Log.i(TAG, "ARCore: device not on supported list")
                ArSessionResult.Unavailable(ArUnavailableReason.UNSUPPORTED_DEVICE)
            }
            availability == ArCoreApk.Availability.UNKNOWN_ERROR ||
            availability == ArCoreApk.Availability.UNKNOWN_CHECKING ||
            availability == ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> {
                ArSessionResult.Unavailable(ArUnavailableReason.UNKNOWN)
            }
            else -> {
                // Supported — attempt to create a session
                val session = Session(context)
                val config = Config(session).apply {
                    updateMode        = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode  = Config.PlaneFindingMode.DISABLED
                    focusMode         = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                }
                session.configure(config)
                ArSessionResult.Available(session)
            }
        }
    } catch (e: com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException) {
        Log.i(TAG, "ARCore: device not compatible")
        ArSessionResult.Unavailable(ArUnavailableReason.UNSUPPORTED_DEVICE)
    } catch (e: com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException) {
        Log.i(TAG, "ARCore: not installed")
        ArSessionResult.Unavailable(ArUnavailableReason.NOT_INSTALLED)
    } catch (e: com.google.ar.core.exceptions.UnavailableSdkTooOldException) {
        Log.i(TAG, "ARCore: SDK too old")
        ArSessionResult.Unavailable(ArUnavailableReason.SDK_TOO_OLD)
    } catch (e: SecurityException) {
        Log.e(TAG, "ARCore: missing CAMERA permission")
        ArSessionResult.Unavailable(ArUnavailableReason.NO_CAMERA_PERMISSION)
    } catch (e: Exception) {
        Log.e(TAG, "ARCore: unknown error — ${e.message}")
        ArSessionResult.Unavailable(ArUnavailableReason.UNKNOWN)
    }
}

/** Human-readable status line for the UI */
fun ArUnavailableReason.toUiMessage(): String = when (this) {
    ArUnavailableReason.UNSUPPORTED_DEVICE ->
        "Step Tracking active (ARCore not supported on this device)"
    ArUnavailableReason.NOT_INSTALLED ->
        "Step Tracking active (install 'Google Play Services for AR' for enhanced tracking)"
    ArUnavailableReason.SDK_TOO_OLD ->
        "Step Tracking active (update the app for AR support)"
    ArUnavailableReason.NO_CAMERA_PERMISSION ->
        "Step Tracking active (grant Camera permission for AR)"
    ArUnavailableReason.UNKNOWN ->
        "Step Tracking active"
}
