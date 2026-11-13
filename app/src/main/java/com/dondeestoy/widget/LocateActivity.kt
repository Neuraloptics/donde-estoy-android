package com.dondeestoy.widget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

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
                window.decorView.postDelayed({ if (!isFinishing) finish() }, 20000)
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
                }, 20000)
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
        Executors.newSingleThreadExecutor().execute {
            var bmp: Bitmap? = null
            var remote = false
            val lat = loc.latitude
            val lon = loc.longitude
            val urls = listOf(
                "https://staticmap.openstreetmap.de/staticmap.php?center=$lat,$lon&zoom=15&size=600x360&markers=$lat,$lon,red-pushpin",
                "https://maps.wikimedia.org/img/osm-intl,15,$lat,$lon,600x360.png"
            )
            for (u in urls) {
                try {
                    val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "DondeEstoyWidget/1.0 (Android)")
                    }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        conn.inputStream.use { input ->
                            bmp = BitmapFactory.decodeStream(input)
                        }
                    }
                    conn.disconnect()
                    if (bmp != null) {
                        remote = true
                        if (!u.contains("markers=")) {
                            bmp = addPin(bmp!!)
                        }
                        break
                    }
                } catch (_: Exception) {
                }
            }
            if (bmp == null) {
                bmp = drawFallbackMap(loc, city)
            }
            val mapBmp = bmp
            val status = if (remote) "Ubicado ✓" else "Ubicado ✓ (sin mapa)"
            runOnUiThread {
                DondeEstoyWidgetProvider.updateStatus(this, status, city, mapBmp)
                if (!isFinishing) finish()
            }
        }
    }

    private fun fail(msg: String) {
        DondeEstoyWidgetProvider.updateStatus(this, msg)
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 1500)
    }

    private fun addPin(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val cx = bmp.width / 2f
        val cy = bmp.height / 2f
        val pin = Paint().apply { color = Color.rgb(220, 40, 40); isAntiAlias = true }
        c.drawCircle(cx, cy - 8f, 14f, pin)
        val inner = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        c.drawCircle(cx, cy - 8f, 6f, inner)
        val stem = Paint().apply { color = Color.rgb(220, 40, 40); strokeWidth = 5f; isAntiAlias = true }
        c.drawLine(cx, cy + 4f, cx, cy + 18f, stem)
        return bmp
    }

    private fun drawFallbackMap(loc: Location, city: String): Bitmap {
        val w = 600
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(30, 36, 48))
        val grid = Paint().apply { color = Color.argb(40, 255, 255, 255); strokeWidth = 2f }
        for (x in 0..w step 60) c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), grid)
        for (y in 0..h step 60) c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), grid)
        val pin = Paint().apply { color = Color.rgb(255, 90, 90); isAntiAlias = true }
        c.drawCircle(w / 2f, h / 2f - 10f, 18f, pin)
        val pinInner = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        c.drawCircle(w / 2f, h / 2f - 10f, 7f, pinInner)
        val text = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        val label = if (city.isNotBlank()) city else "Ubicación"
        c.drawText(label.take(28), 24f, h - 56f, text)
        val sub = Paint().apply { color = Color.argb(200, 255, 255, 255); textSize = 22f; isAntiAlias = true }
        c.drawText(String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude), 24f, h - 24f, sub)
        return bmp
    }
}
