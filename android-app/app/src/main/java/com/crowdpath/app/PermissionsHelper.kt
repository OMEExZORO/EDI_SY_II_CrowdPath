package com.crowdpath.app

import android.Manifest
import android.app.Activity
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener

/**
 * Centralised permission-request helper using Dexter.
 */
object PermissionsHelper {

    private val REQUIRED_PERMISSIONS = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    )

    /**
     * Request all necessary permissions.
     *
     * @param onGranted called when all permissions are granted.
     * @param onDenied  called if any permission is permanently denied.
     */
    fun requestAll(
        activity: Activity,
        onGranted: () -> Unit,
        onDenied: (List<String>) -> Unit
    ) {
        Dexter.withActivity(activity)
            .withPermissions(REQUIRED_PERMISSIONS)
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.areAllPermissionsGranted()) {
                        onGranted()
                    } else {
                        val denied = report.deniedPermissionResponses
                            .map { it.permissionName }
                        onDenied(denied)
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    permissions: MutableList<PermissionRequest>,
                    token: PermissionToken
                ) {
                    token.continuePermissionRequest()
                }
            })
            .onSameThread()
            .check()
    }
}
