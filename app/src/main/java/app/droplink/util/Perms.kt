package app.droplink.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Which runtime permissions each feature needs on which API level. */
object Perms {

    /** Wi-Fi Direct and the local-only hotspot: NEARBY_WIFI_DEVICES on 13+, location on 8–12. */
    fun nearbyWifi(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun camera(): List<String> = listOf(Manifest.permission.CAMERA)

    fun notifications(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

    /** Android 8–9 write received files to the public Download folder. */
    @Suppress("DEPRECATION")
    fun legacyStorage(): List<String> = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        emptyList()
    }

    fun granted(context: Context, permissions: List<String>): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** On 8–12, Wi-Fi scanning APIs also need the location switch on. Not needed with NEARBY_WIFI_DEVICES. */
    fun needsLocationServices(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun locationServicesOn(context: Context): Boolean {
        if (!needsLocationServices()) return true
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun wifiDirectReady(context: Context): Boolean = granted(context, nearbyWifi()) && locationServicesOn(context)
    fun hotspotReady(context: Context): Boolean = wifiDirectReady(context)
}
