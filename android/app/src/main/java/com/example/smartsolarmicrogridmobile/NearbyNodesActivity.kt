package com.example.smartsolarmicrogridmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nearby-node discovery. Google supplies the map; [NodeApi] supplies the registered
 * microgrid nodes. Markers always come from API data — never Google Places.
 *
 * MainActivity (plain Activity) cannot host SupportMapFragment, hence this separate
 * FragmentActivity. It returns the chosen node ID to MainActivity, which opens its
 * existing slot picker; capacity at the chosen time is still validated server-side.
 */
class NearbyNodesActivity : FragmentActivity(), OnMapReadyCallback {
    companion object {
        const val EXTRA_NODE_ID = "nodeId"
        private const val DEFAULT_LAT = 6.9271 // Colombo fallback until a fix or manual point exists.
        private const val DEFAULT_LON = 79.8612
        private val DAY_NAMES = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var map: GoogleMap? = null
    private var session: AccountSession? = null
    private val nodesById = mutableMapOf<String, JSONObject>()
    private var centerLat = DEFAULT_LAT
    private var centerLon = DEFAULT_LON
    private var hasFix = false
    private var radiusKm = 10.0

    private lateinit var status: TextView
    private lateinit var detailCard: View
    private lateinit var detailTitle: TextView
    private lateinit var detailAddress: TextView
    private lateinit var detailDistance: TextView
    private lateinit var detailHours: TextView
    private lateinit var detailCapacity: TextView
    private lateinit var detailSlots: TextView
    private var selectedNodeId: String? = null

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) locate(disableWhenDenied = false)
        else {
            status.text = "Location unavailable — browse the map or long-press to search around a point."
            Toast.makeText(this, "Permission denied. Browsing manually instead.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_nodes)
        status = findViewById(R.id.statusText)
        detailCard = findViewById(R.id.detailCard)
        detailTitle = findViewById(R.id.detailTitle)
        detailAddress = findViewById(R.id.detailAddress)
        detailDistance = findViewById(R.id.detailDistance)
        detailHours = findViewById(R.id.detailHours)
        detailCapacity = findViewById(R.id.detailCapacity)
        detailSlots = findViewById(R.id.detailSlots)
        session = SessionStore(this).use { it.load() }
        if (session == null) {
            Toast.makeText(this, "Please sign in first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<Button>(R.id.radius5).setOnClickListener { setRadius(5.0) }
        findViewById<Button>(R.id.radius10).setOnClickListener { setRadius(10.0) }
        findViewById<Button>(R.id.radius25).setOnClickListener { setRadius(25.0) }
        findViewById<Button>(R.id.locateButton).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.closeButton).setOnClickListener { detailCard.visibility = View.GONE }
        findViewById<Button>(R.id.reserveButton).setOnClickListener {
            val id = selectedNodeId ?: return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra(EXTRA_NODE_ID, id))
            finish()
        }
        (supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync(this)
        requestLocation()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- Map setup ----
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.setOnMarkerClickListener { marker ->
            (marker.tag as? String)?.let { showDetails(it) }
            true
        }
        // Manual area selection when location is denied or imprecise.
        googleMap.setOnMapLongClickListener { point ->
            centerLat = point.latitude
            centerLon = point.longitude
            hasFix = true
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 12f))
            reload()
        }
        try {
            if (hasLocationPermission()) googleMap.isMyLocationEnabled = true
        } catch (_: SecurityException) { /* permission revoked mid-flow; manual browsing still works */ }
        loadAllNodes()
    }

    /** Step 3: plot actual registered nodes before any location filtering. */
    private fun loadAllNodes() {
        val current = session ?: return
        status.text = "Loading grid nodes…"
        worker.execute {
            val result = runCatching { NodeApi(current.server).list(current.token) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.fold({ rows ->
                    cache(rows)
                    plot(rows, announceTotal = true)
                    if (!hasFix) {
                        val first = firstPosition(rows)
                        val target = first ?: LatLng(DEFAULT_LAT, DEFAULT_LON)
                        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(target, if (first != null) 11f else 7f))
                        status.text =
                            "Showing ${rows.length()} nodes. Allow location or long-press the map for nearby results."
                    }
                    // When a fix already exists, plot() triggers reload() to narrow to the radius.
                }) { error ->
                    status.text = if (error is ApiFailure && error.status == 401) {
                        SessionStore(this).use { it.clear() }
                        "Session expired. Sign in again from the main screen."
                    } else "Could not load nodes. Check your connection and retry."
                }
            }
        }
    }

    // ---- Location ----
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocation() {
        if (hasLocationPermission()) locate(disableWhenDenied = false)
        else permissionRequest.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** Coarse (approximate) fixes are accepted as-is; radius filtering absorbs the imprecision. */
    private fun locate(disableWhenDenied: Boolean) {
        if (!hasLocationPermission()) {
            if (disableWhenDenied) status.text = "Location unavailable — browse the map or long-press to search around a point."
            return
        }
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { location ->
                    if (location == null) {
                        status.text = "No location fix yet — browse the map or long-press to search around a point."
                        return@addOnSuccessListener
                    }
                    centerLat = location.latitude
                    centerLon = location.longitude
                    hasFix = true
                    try {
                        map?.isMyLocationEnabled = true
                    } catch (_: SecurityException) { }
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLon), 12f))
                    reload()
                }
                .addOnFailureListener {
                    status.text = "Could not read your location — browse the map or long-press to search around a point."
                }
        } catch (_: SecurityException) {
            status.text = "Location unavailable — browse the map or long-press to search around a point."
        }
    }

    // ---- Nearby filtering ----
    private fun setRadius(km: Double) {
        radiusKm = km
        detailCard.visibility = View.GONE
        if (!hasFix && nodesById.isEmpty()) loadAllNodes() else reload()
    }

    private fun reload() {
        val current = session ?: return
        if (!hasFix) {
            // No fix yet: keep the full plot from loadAllNodes() instead of filtering around a default.
            return
        }
        status.text = "Searching within ${radiusKm.toInt()} km…"
        worker.execute {
            // Central filtering first; older servers without /nearby fall back to client-side haversine.
            val result = runCatching { NodeApi(current.server).nearby(centerLat, centerLon, radiusKm, current.token) }
                .recoverCatching { NodeApi(current.server).list(current.token).let { filterNearby(it) } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.fold({ rows ->
                    cache(rows)
                    plot(rows, announceTotal = false)
                    status.text = if (rows.length() == 0)
                        "No nodes within ${radiusKm.toInt()} km — try a larger radius or another area."
                    else "${rows.length()} node(s) within ${radiusKm.toInt()} km. Tap a marker for details."
                }) { error ->
                    status.text = if (error is ApiFailure && error.status == 401) {
                        SessionStore(this).use { it.clear() }
                        "Session expired. Sign in again from the main screen."
                    } else "Search failed. Check your connection and retry."
                }
            }
        }
    }

    private fun filterNearby(rows: JSONArray): JSONArray {
        val out = JSONArray()
        data class Scored(val node: JSONObject, val distance: Double)
        val scored = mutableListOf<Scored>()
        for (i in 0 until rows.length()) {
            val node = rows.getJSONObject(i)
            if (!node.optBoolean("isActive", true)) continue
            val distance = haversineKm(centerLat, centerLon, node.optDouble("latitude"), node.optDouble("longitude"))
            if (distance <= radiusKm) scored.add(Scored(node, distance))
        }
        scored.sortBy { it.distance }
        scored.forEach { out.put(it.node) }
        return out
    }

    // ---- Markers + details ----
    private fun cache(rows: JSONArray) {
        for (i in 0 until rows.length()) {
            val node = rows.getJSONObject(i)
            nodesById[node.optString("id")] = node
        }
    }

    private fun firstPosition(rows: JSONArray): LatLng? {
        for (i in 0 until rows.length()) {
            val node = rows.getJSONObject(i)
            if (node.has("latitude") && node.has("longitude")) {
                return LatLng(node.optDouble("latitude"), node.optDouble("longitude"))
            }
        }
        return null
    }

    private fun plot(rows: JSONArray, announceTotal: Boolean) {
        val googleMap = map ?: return
        googleMap.clear()
        for (i in 0 until rows.length()) {
            val node = rows.getJSONObject(i)
            if (!node.has("latitude") || !node.has("longitude")) continue
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(LatLng(node.optDouble("latitude"), node.optDouble("longitude")))
                    .title(node.optString("name"))
            )
            marker?.tag = node.optString("id")
        }
        if (announceTotal && hasFix) reload()
    }

    private fun showDetails(nodeId: String) {
        val node = nodesById[nodeId] ?: return
        selectedNodeId = nodeId
        val distance = haversineKm(centerLat, centerLon, node.optDouble("latitude"), node.optDouble("longitude"))
        detailTitle.text = node.optString("name")
        detailAddress.text = node.optString("address")
        detailDistance.text = if (hasFix) String.format("%.1f km away", distance) else "Distance unknown"
        detailHours.text = formatHours(node.optJSONArray("schedule")) +
            (node.optString("timeZone").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
        detailCapacity.text = "Capacity: ${node.opt("powerCapacityKw")} kW"
        detailSlots.text = formatSlots(node.optJSONArray("batterySlots"))
        detailCard.visibility = View.VISIBLE
    }

    private fun formatHours(schedule: JSONArray?): String {
        if (schedule == null || schedule.length() == 0) return "Hours: not published"
        val lines = mutableListOf<String>()
        for (i in 0 until schedule.length()) {
            val entry = schedule.getJSONObject(i)
            val day = DAY_NAMES.getOrElse(entry.optInt("dayOfWeek", -1)) { "Day ${entry.optInt("dayOfWeek")}" }
            lines.add("$day ${entry.optString("opensAt")}–${entry.optString("closesAt")}")
        }
        return "Hours: " + lines.joinToString(", ")
    }

    private fun formatSlots(slots: JSONArray?): String {
        if (slots == null || slots.length() == 0) return "Slots: none configured"
        val lines = mutableListOf<String>()
        for (i in 0 until slots.length()) {
            val slot = slots.getJSONObject(i)
            val state = if (slot.optBoolean("isAvailable", true)) "available" else "unavailable"
            lines.add("${slot.optString("name")} (${slot.opt("capacityKwh")} kWh, $state)")
        }
        return "Slots: " + lines.joinToString("; ")
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthKm * asin(sqrt(a))
    }
}
