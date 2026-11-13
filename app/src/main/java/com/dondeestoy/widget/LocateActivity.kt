package com.dondeestoy.widget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class LocateActivity : Activity() {
    private val REQ_LOC = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC
            )
        } else {
            fetchLocation()
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            fetchLocation()
        } else {
            DondeEstoyWidgetProvider.updateStatus(this, "Sin permiso de ubicación", "Activá el permiso")
            finish()
        }
    }

    private fun fetchLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (last != null) {
                resolveAndShow(last)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = when {
                    lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> LocationManager.GPS_PROVIDER
                }
                lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                    if (loc != null) resolveAndShow(loc) else if (last == null) fail("No se pudo ubicar")
                }
                // safety timeout
                window.decorView.postDelayed({ if (!isFinishing) finish() }, 12000)
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        resolveAndShow(location)
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                window.decorView.postDelayed({
                    try { lm.removeUpdates(listener) } catch (_: Exception) {}
                    if (last == null) fail("No se pudo ubicar")
                    if (!isFinishing) finish()
                }, 12000)
            }
        } catch (se: SecurityException) {
            fail("Sin permiso de ubicación")
        } catch (e: Exception) {
            fail("Error al ubicar")
        }
    }

    private fun resolveAndShow(loc: Location) {
        val city = try {
            val geocoder = Geocoder(this, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            val a = list?.firstOrNull()
            val locality = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            val area = a?.adminArea
            val country = a?.countryName
            when {
                !locality.isNullOrBlank() && !area.isNullOrBlank() && locality != area -> "$locality, $area"
                !locality.isNullOrBlank() -> locality
                !area.isNullOrBlank() -> area
                !country.isNullOrBlank() -> country
                else -> String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)
            }
        } catch (_: Exception) {
            String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)
        }
        DondeEstoyWidgetProvider.updateStatus(this, "Ubicado ✓", city)
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 1500)
    }

    private fun fail(msg: String) {
        DondeEstoyWidgetProvider.updateStatus(this, msg)
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 1500)
    }
}
