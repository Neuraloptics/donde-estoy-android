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
import kotlin.math.*

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
        DondeEstoyWidgetProvider.updateStatus(this, "Ubicando (preciso)…")
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var resolved = false
                var best: Location? = null
                fun consider(loc: Location?) {
                    if (loc == null) return
                    if (best == null || loc.accuracy in 1f..best!!.accuracy) best = loc
                }
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
                if (providers.isEmpty()) {
                    fail("Sin proveedor de ubicación")
                    return
                }
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                for (p in providers) {
                    try {
                        lm.getCurrentLocation(p, null, mainExecutor) { loc ->
                            consider(loc)
                            if (done.incrementAndGet() >= providers.size && !resolved) {
                                resolved = true
                                val chosen = best
                                if (chosen != null) resolveAndShow(chosen) else fail("No se pudo ubicar")
                            }
                        }
                    } catch (_: Exception) {
                        if (done.incrementAndGet() >= providers.size && !resolved) {
                            resolved = true
                            val chosen = best
                            if (chosen != null) resolveAndShow(chosen) else fail("No se pudo ubicar")
                        }
                    }
                }
                window.decorView.postDelayed({
                    if (!resolved) {
                        resolved = true
                        val chosen = best
                        if (chosen != null) resolveAndShow(chosen) else fail("No se pudo ubicar")
                    }
                }, 20000)
            } else {
                var best: Location? = null
                var resolved = false
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (best == null || (location.accuracy > 0f && location.accuracy < best!!.accuracy)) {
                            best = location
                        }
                        if (location.hasAccuracy() && location.accuracy in 1f..12f) {
                            if (!resolved) {
                                resolved = true
                                try { lm.removeUpdates(this) } catch (_: Exception) {}
                                resolveAndShow(location)
                            }
                        }
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                }
                var requested = false
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    try {
                        if (lm.isProviderEnabled(p)) {
                            lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                            requested = true
                        }
                    } catch (_: Exception) {}
                }
                if (!requested) {
                    val last = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    if (last != null) resolveAndShow(last) else fail("Sin proveedor de ubicación")
                } else {
                    window.decorView.postDelayed({
                        try { lm.removeUpdates(listener) } catch (_: Exception) {}
                        if (!resolved) {
                            resolved = true
                            val chosen = best
                            if (chosen == null) fail("No se pudo ubicar") else resolveAndShow(chosen)
                        }
                    }, 15000)
                }
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
            val thoroughfare = a?.thoroughfare
            val subThoroughfare = a?.subThoroughfare
            val locality = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            val area = a?.adminArea
            val street = listOfNotNull(thoroughfare, subThoroughfare).joinToString(" ").trim()
            when {
                street.isNotBlank() && !locality.isNullOrBlank() -> "$street, $locality"
                !locality.isNullOrBlank() && !area.isNullOrBlank() && locality != area -> "$locality, $area"
                !locality.isNullOrBlank() -> locality
                !area.isNullOrBlank() -> area
                !a?.countryName.isNullOrBlank() -> a?.countryName
                else -> String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
            }
        } catch (_: Exception) {
            String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
        }
        DondeEstoyWidgetProvider.updateStatus(this, "Ubicado ✓ (±${loc.accuracy.toInt()}m)", city)
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
                bmp = fetchTileMap(lat, lon)
                if (bmp != null) remote = true
            }
            if (bmp == null) {
                bmp = drawFallbackMap(loc, city)
            }
            val mapBmp = bmp
            val acc = loc.accuracy.toInt()
            val status = if (remote) "Ubicado ✓ (±${acc}m)" else "Ubicado ✓ (±${acc}m, sin mapa)"
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

    private fun fetchTileMap(lat: Double, lon: Double): Bitmap? {
        return try {
            val z = 17
            val n = 2.0.pow(z.toDouble())
            val xFloat = (lon + 180.0) / 360.0 * n
            val latRad = Math.toRadians(lat)
            val yFloat = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
            val xTile = floor(xFloat).toInt()
            val yTile = floor(yFloat).toInt()
            val tilesX = 3
            val tilesY = 2
            val tileSize = 256
            val startX = xTile - 1
            val startY = yTile - 1
            val px = ((xFloat - startX) * tileSize).toFloat()
            val py = ((yFloat - startY) * tileSize).toFloat()
            val out = Bitmap.createBitmap(tileSize * tilesX, tileSize * tilesY, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            var ok = false
            for (dx in 0 until tilesX) {
                for (dy in 0 until tilesY) {
                    val tx = startX + dx
                    val ty = startY + dy
                    val tileUrl = "https://tile.openstreetmap.org/$z/$tx/$ty.png"
                    try {
                        val conn = (URL(tileUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 8000
                            setRequestProperty("User-Agent", "DondeEstoyWidget/1.0 (Android)")
                        }
                        if (conn.responseCode in 200..299) {
                            conn.inputStream.use { input ->
                                val tb = BitmapFactory.decodeStream(input)
                                if (tb != null) {
                                    canvas.drawBitmap(tb, (dx * tileSize).toFloat(), (dy * tileSize).toFloat(), null)
                                    tb.recycle()
                                    ok = true
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
            if (!ok) null else {
                val pin = Paint().apply { color = Color.rgb(220, 40, 40); isAntiAlias = true }
                canvas.drawCircle(px, py - 8f, 14f, pin)
                val inner = Paint().apply { color = Color.WHITE; isAntiAlias = true }
                canvas.drawCircle(px, py - 8f, 6f, inner)
                val stem = Paint().apply { color = Color.rgb(220, 40, 40); strokeWidth = 5f; isAntiAlias = true }
                canvas.drawLine(px, py + 4f, px, py + 18f, stem)
                out
            }
        } catch (_: Exception) {
            null
        }
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
