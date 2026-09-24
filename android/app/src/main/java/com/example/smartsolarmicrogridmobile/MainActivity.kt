package com.example.smartsolarmicrogridmobile

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Native Android account screens; all operations go through the REST API. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val lookupWorker = Executors.newSingleThreadExecutor()
    private var pageGeneration = 0L
    private lateinit var content: LinearLayout
    private lateinit var message: TextView
    private var session: AccountSession? = null
    private var server = BuildConfig.API_BASE_URL
    private var busy = false
    private val controls = mutableListOf<Button>()

    // Reservation flow state.
    private var draftNode: JSONObject? = null
    private var draftSlot: JSONObject? = null
    private var editingReservationId: String? = null
    private var draftStart: ZonedDateTime? = null
    private var draftEnd: ZonedDateTime? = null
    private var reservationView: String = "current"
    private var reservationSearch: String = ""
    private val nearbyRequest = 9001
    // Node chosen on the map; consumed in onResume after session revalidation so the
    // slot picker cannot race home-screen refresh when returning from the map.
    private var pendingNearbyNodeId: String? = null
    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
    // Set by showCompleteTransaction() so onActivityResult can fill the right field after a scan.
    private var scannedTokenField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
        work({
            val saved = SessionStore(this).use { it.load() }
            if (saved == null) null else {
                if (Instant.parse(saved.expires).isBefore(Instant.now())) throw ApiFailure(401, "Your session expired. Please sign in again.")
                val current = AccountApi(saved.server).request("/user", token = saved.token)
                checkMobileRole(current)
                saved.copy(user = current).also { updated -> SessionStore(this).use { it.save(updated) } }
            }
        }) { restored ->
            session = restored
            if (restored != null) { server = restored.server; showHome() }
        }
    }
    override fun onResume() {
        super.onResume()
        // Revalidate when returning to the app so remote deactivation is observed.
        val current = session
        if (current != null && !busy) work({ validated(current) }) {
            session = it
            pendingNearbyNodeId?.let { nodeId ->
                pendingNearbyNodeId = null
                openNodeFromNearby(nodeId)
            } ?: showHome()
        }
    }
    override fun onDestroy() {
        worker.shutdownNow()
        lookupWorker.shutdownNow()
        super.onDestroy()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == nearbyRequest) {
            if (resultCode == RESULT_OK) {
                intent?.getStringExtra(NearbyNodesActivity.EXTRA_NODE_ID)?.let { pendingNearbyNodeId = it }
            }
            return
        }
        val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent) ?: return
        val text = result.contents ?: return // user cancelled the scan
        scannedTokenField?.setText(text)
    }
    private fun checkMobileRole(user: JSONObject) {
        if (user.getString("role") !in listOf("PROSUMER", "GRID_OPERATOR"))
            throw ApiFailure(403, "Backoffice accounts use the web portal.")
    }
    private fun validated(current: AccountSession): AccountSession {
        val user = AccountApi(current.server).request("/user", token = current.token)
        checkMobileRole(user)
        return current.copy(user = user).also { updated -> SessionStore(this).use { it.save(updated) } }
    }
    // ---- Shared styling helpers (plain Views only; palette from colors.xml) ----
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    /** Rounded fill, used for input boxes and outline buttons. */
    private fun roundedDrawable(fillColor: Int, strokeColor: Int? = null, strokeWidthDp: Int = 1, radiusDp: Int = 10): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }
    private fun inputBackground(): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundedDrawable(color(R.color.solar_surface), color(R.color.solar_border_focused), 2))
            addState(intArrayOf(), roundedDrawable(color(R.color.solar_surface), color(R.color.solar_border)))
        }
    }
    private fun buttonBackground(primary: Boolean): StateListDrawable {
        val fill = if (primary) color(R.color.solar_green) else color(R.color.solar_surface)
        val stroke = if (primary) null else color(R.color.solar_border)
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), roundedDrawable(color(R.color.solar_disabled), stroke, radiusDp = 10))
            addState(intArrayOf(android.R.attr.state_pressed), roundedDrawable(if (primary) color(R.color.solar_green_hover) else color(R.color.solar_background), stroke, radiusDp = 10))
            addState(intArrayOf(), roundedDrawable(fill, stroke, radiusDp = 10))
        }
    }
    private fun page(title: String, description: String) {
        pageGeneration++
        busy = false
        controls.clear()
        // Only showCompleteTransaction() re-sets this; every other screen must not hold a stale scan target.
        scannedTokenField = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.solar_background))
        }
        // Header bar sits above the scroll area so it never scrolls away.
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.solar_green_dark))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(TextView(this@MainActivity).apply {
                text = "SOLARGRID"; textSize = 14f; setTextColor(color(R.color.white))
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD); letterSpacing = 0.08f
            })
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 22f; setTextColor(color(R.color.white))
                typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, 0)
            })
            root.addView(this)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(dp(20) + bars.left, dp(20) + bars.top, dp(20) + bars.right, dp(20))
                insets
            }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true; addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, 0, bars.right, bars.bottom)
                insets
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        label(description, 15f, color(R.color.solar_muted_green))
        message = label("", 15f, color(R.color.solar_error)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    }
    private fun label(text: String, size: Float = 16f, color: Int = color(R.color.solar_body_text)): TextView {
        return TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(10))
            content.addView(this)
        }
    }
    /** Section heading used above a group of related fields or buttons. */
    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.solar_body_text)); setPadding(0, dp(20), 0, dp(8))
            content.addView(this)
        }
    }
    /** Colored status pill, e.g. for PENDING / APPROVED / COMPLETED / CANCELLED / REJECTED. */
    private fun statusPill(status: String): TextView {
        val (bg, fg) = when (status) {
            "PENDING" -> R.color.solar_status_pending_bg to R.color.solar_status_pending_fg
            "APPROVED" -> R.color.solar_status_approved_bg to R.color.solar_status_approved_fg
            "COMPLETED" -> R.color.solar_status_completed_bg to R.color.solar_status_completed_fg
            else -> R.color.solar_status_negative_bg to R.color.solar_status_negative_fg
        }
        return TextView(this).apply {
            text = status; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color(fg))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedDrawable(color(bg), radiusDp = 20)
            content.addView(this, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        }
    }
    private fun fieldLabel(title: String): TextView {
        return TextView(this).apply {
            text = title; textSize = 13f; setTextColor(color(R.color.solar_muted_green))
            typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2), dp(4), 0, dp(6))
            content.addView(this)
        }
    }
    private fun field(title: String, value: String = "", password: Boolean = false, email: Boolean = false, numeric: Boolean = false): EditText {
        val titleView = fieldLabel(title)
        return EditText(this).apply {
            id = View.generateViewId(); titleView.labelFor = id
            setText(value); hint = title; setSingleLine(true)
            setHintTextColor(color(R.color.solar_muted_green))
            setTextColor(color(R.color.solar_body_text))
            background = inputBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = when { password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT }
            // Passwords and tokens must not be included in Android view-state snapshots.
            isSaveEnabled = false
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
    }
    private fun radioChoice(title: String, options: List<String>, selected: String = options.first()): RadioGroup {
        fieldLabel(title)
        return RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            options.forEach { option ->
                addView(RadioButton(this@MainActivity).apply {
                    text = option; id = View.generateViewId(); isChecked = option == selected
                    setTextColor(color(R.color.solar_body_text))
                    buttonTintList = ColorStateList.valueOf(color(R.color.solar_green))
                    setPadding(dp(8), dp(6), 0, dp(6))
                })
            }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
    }
    private fun RadioGroup.selectedText(): String = findViewById<RadioButton>(checkedRadioButtonId).text.toString()
    private fun button(title: String, primary: Boolean = true, action: () -> Unit): Button {
        return Button(this).apply {
            text = title; isAllCaps = false; isEnabled = !busy
            textSize = 15f
            typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (primary) color(R.color.white) else color(R.color.solar_body_text))
            background = buttonBackground(primary)
            gravity = Gravity.CENTER
            stateListAnimator = null
            val vPad = dp(14)
            setPadding(dp(16), vPad, dp(16), vPad)
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            controls.add(this)
        }
    }
    private fun showLogin(info: String = "") {
        pendingNearbyNodeId = null
        page("Welcome back", "Sign in as a Prosumer or Grid Operator.")
        if (info.isNotBlank()) label(info, 16f, color(R.color.solar_muted_green))
        // Originally an editable "Service address" field here (by SupunLiyanage88, commit f987550)
        // let AccountFlowTest point sign-in at a disposable/CI test server via an apiUrl
        // instrumentation arg. Removed from the UI: nothing in the assignment brief calls for a
        // user-editable server address, and letting an end user redirect the app to an arbitrary
        // server is a real security concern. Kept here, commented, in case a build-time/CI
        // override needs reintroducing later without exposing it to real users:
        // val address = field("Service address", server)
        val email = field("Email address", email = true)
        val password = field("Password", password = true)
        button("Sign in") {
            val credentials = JSONObject().put("email", email.text.toString().trim()).put("password", password.text.toString())
            work({
                val response = AccountApi(server).request("/login", "POST", credentials)
                val user = response.getJSONObject("user"); checkMobileRole(user)
                AccountSession(server, response.getString("token"), response.getString("expiresAtUtc"), user)
                    .also { current -> SessionStore(this).use { it.save(current) } }
            }) { current -> session = current; password.setText(""); showHome() }
        }
        button("Create a prosumer account") { showRegister() }
    }
    private fun showRegister() {
        page("Join the solar community", "Your NIC identifies your account. Backoffice approval is required before your first sign-in.")
        val name = field("Full name")
        val nic = field("NIC (12 digits or 9 digits followed by V/X)")
        val email = field("Email address", email = true)
        val password = field("Password (8-72 UTF-8 bytes)", password = true)
        val confirm = field("Confirm password", password = true)
        button("Register") {
            if (password.text.toString() != confirm.text.toString()) { message.text = "Passwords do not match."; return@button }
            val body = JSONObject().put("userName", name.text.toString().trim()).put("nic", nic.text.toString().trim())
                .put("email", email.text.toString().trim()).put("password", password.text.toString())
            work({ AccountApi(server).request("/register", "POST", body) }) {
                password.setText(""); confirm.setText(""); showLogin("Registration received. Ask Backoffice to activate your account, then sign in.")
            }
        }
        button("Back to sign in", primary = false) { showLogin() }
    }
    private fun showHome() {
        val current = session ?: return showLogin()
        val user = current.user
        val prosumer = user.getString("role") == "PROSUMER"
        page(if (prosumer) "Prosumer home" else "Grid Operator home", "Welcome, ${user.getString("userName")}")
        label("NIC: ${user.getString("nic")}")
        label(user.getString("email"))
        if (user.optBoolean("deactivationRequested")) statusPill("PENDING") else statusPill("APPROVED").text = "ACTIVE"
        if (user.optBoolean("deactivationRequested")) label("Your deactivation request is awaiting Backoffice review.", 14f, color(R.color.solar_muted_green))
        button("Edit my profile") { showProfile() }
        button("Refresh account", primary = false) { work({ validated(current) }) { session = it; showHome() } }
        if (prosumer && !user.optBoolean("deactivationRequested")) button("Request deactivation", primary = false) {
            AlertDialog.Builder(this).setTitle("Request account deactivation?")
                .setMessage("Backoffice will review your request. Once deactivated, only Backoffice can reactivate your account.")
                .setNegativeButton("Cancel", null).setPositiveButton("Request") { _, _ ->
                    work({
                        AccountApi(current.server).request("/user/deactivation", "POST", token = current.token)
                        validated(current)
                    }) { session = it; showHome() }
                }.show()
        }
        sectionTitle("Reservations")
        work({ ReservationApi(current.server).summary(current.token) }) { summary ->
            label("Pending: ${summary.optLong("pendingCount")}    Upcoming approved: ${summary.optLong("approvedFutureCount")}", 14f, color(R.color.solar_muted_green))
        }
        if (prosumer) {
            button("Reserve a slot") { draftNode = null; draftSlot = null; editingReservationId = null; draftStart = null; draftEnd = null; showNodePicker() }
            button("Find nodes nearby") { startActivityForResult(Intent(this, NearbyNodesActivity::class.java), nearbyRequest) }
            button("My reservations") { reservationSearch = ""; showReservationList("current", "") }
        } else {
            button("Pending approvals") { showPendingApprovals() }
            button("Bookings") { reservationSearch = ""; showReservationList("current", "") }
            button("Complete a transaction") { showCompleteTransaction() }
        }
        button("Sign out", primary = false) { work({ SessionStore(this).use { it.clear() } }) { session = null; showLogin("You have signed out.") } }
    }
    private fun showProfile() {
        val current = session ?: return showLogin()
        page("My profile", "Your NIC cannot be changed. Your account permissions are managed by Backoffice.")
        label("NIC: ${current.user.getString("nic")}")
        val name = field("Full name", current.user.getString("userName"))
        val email = field("Email address", current.user.getString("email"), email = true)
        button("Save profile") {
            val body = JSONObject().put("userName", name.text.toString().trim()).put("email", email.text.toString().trim())
            work({
                val user = AccountApi(current.server).request("/user", "PATCH", body, current.token)
                current.copy(user = user).also { updated -> SessionStore(this).use { it.save(updated) } }
            }) { session = it; showHome() }
        }
        button("Cancel", primary = false) { showHome() }
    }
    // ---- Prosumer reservation flow ----
    /** Opens the existing slot picker with the node chosen on the nearby map selected. */
    private fun openNodeFromNearby(nodeId: String) {
        val current = session ?: return showLogin()
        page("Choose a location", "Pick a grid node to reserve a battery slot.")
        work({ NodeApi(current.server).get(nodeId, current.token) }) { node ->
            draftNode = node; draftSlot = null; editingReservationId = null
            showSlotPicker(node)
        }
        button("Back", primary = false) { showHome() }
    }
    private fun showNodePicker() {
        val current = session ?: return showLogin()
        page("Choose a location", "Pick a grid node to reserve a battery slot.")
        work({ NodeApi(current.server).list(current.token) }) { nodes ->
            if (nodes.length() == 0) label("No active nodes are available right now.")
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                button("${node.getString("name")} - ${node.getString("address")}", primary = false) { draftNode = node; showSlotPicker(node) }
            }
        }
    }
    private fun showSlotPicker(node: JSONObject) {
        page(node.getString("name"), "Choose an available battery slot.")
        val slots = node.getJSONArray("batterySlots")
        if (slots.length() == 0) label("This node has no battery slots configured.")
        for (i in 0 until slots.length()) {
            val slot = slots.getJSONObject(i)
            val available = slot.optBoolean("isAvailable", true)
            val caption = "${slot.getString("name")} - ${slot.get("capacityKwh")} kWh" + if (!available) " (unavailable)" else ""
            val b = button(caption, primary = false) { draftSlot = slot; showReservationForm() }
            if (!available) { b.isEnabled = false; b.setOnClickListener(null) }
        }
        button("Back", primary = false) { showNodePicker() }
    }
    private fun pickDateTime(initial: ZonedDateTime?, onPicked: (ZonedDateTime) -> Unit) {
        val base = initial ?: ZonedDateTime.now(ZoneId.systemDefault()).plusHours(1)
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                onPicked(ZonedDateTime.of(y, m + 1, d, h, min, 0, 0, ZoneId.systemDefault()))
            }, base.hour, base.minute, false).show()
        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
    }
    private fun showReservationForm() {
        val current = session ?: return showLogin()
        val editing = editingReservationId != null
        page(if (editing) "Modify reservation" else "New reservation",
            "Times are shown in your device's local time zone; the node's own operating hours are checked on the server.")
        if (draftNode != null || draftSlot != null) {
            sectionTitle("Location")
            draftNode?.let { label("Node: ${it.optString("name")}") }
            draftSlot?.let { label("Slot: ${it.optString("name")} (${it.opt("capacityKwh")} kWh capacity)") }
        }
        sectionTitle("Details")
        val direction = radioChoice("Direction", listOf("DROP_OFF", "CHARGING"))
        val kwh = field("Requested kWh", numeric = true)
        sectionTitle("Schedule")
        button("Start: ${draftStart?.format(dateTimeFormat) ?: "Choose"}", primary = false) {
            pickDateTime(draftStart) { picked -> draftStart = picked; showReservationForm() }
        }
        button("End: ${draftEnd?.format(dateTimeFormat) ?: "Choose"}", primary = false) {
            pickDateTime(draftEnd) { picked -> draftEnd = picked; showReservationForm() }
        }
        button(if (editing) "Save changes" else "Reserve") {
            val start = draftStart; val end = draftEnd
            if (start == null || end == null) { message.text = "Choose a start and end time."; return@button }
            if (end <= start) { message.text = "End must be after start."; return@button }
            val kwhValue = kwh.text.toString().toDoubleOrNull()
            if (kwhValue == null || kwhValue <= 0) { message.text = "Enter the requested kWh."; return@button }
            val body = JSONObject()
                .put("nodeId", draftNode?.optString("id") ?: "")
                .put("slotId", draftSlot?.optString("id") ?: "")
                .put("direction", direction.selectedText())
                .put("requestedKwh", kwhValue)
                .put("startsAtUtc", start.toInstant().toString())
                .put("endsAtUtc", end.toInstant().toString())
            val id = editingReservationId
            work({
                if (id != null) ReservationApi(current.server).update(id, body, current.token)
                else ReservationApi(current.server).create(body, current.token)
            }) { reservation ->
                editingReservationId = null; draftStart = null; draftEnd = null
                showReservationSummary(if (id != null) "updated" else "requested", reservation, if (id != null) reservationView else "pending")
            }
        }
        button("Cancel", primary = false) { if (editing) showReservationList(reservationView, reservationSearch) else showHome() }
    }
    private fun showReservationList(view: String, search: String = reservationSearch) {
        val current = session ?: return showLogin()
        reservationView = view
        reservationSearch = search
        val isOperator = current.user.optString("role") == "GRID_OPERATOR"
        page(if (isOperator) "Bookings" else "My reservations",
            "Filter by status. Search by booking ID, NIC, node/slot ID, or direction.")
        val labelFor = mapOf("current" to "Current", "pending" to "Pending", "history" to "History")
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            labelFor.forEach { (key, title) ->
                val active = key == view
                addView(Button(this@MainActivity).apply {
                    text = title; isAllCaps = false; textSize = 13f
                    isEnabled = !busy
                    controls.add(this)
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (active) color(R.color.white) else color(R.color.solar_body_text))
                    background = roundedDrawable(if (active) color(R.color.solar_green) else color(R.color.solar_surface),
                        if (active) null else color(R.color.solar_border), radiusDp = 10)
                    stateListAnimator = null
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener { showReservationList(key, reservationSearch) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (key != "history") dp(8) else 0 })
            }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        val searchField = field("Search bookings", search)
        button("Search") { showReservationList(view, searchField.text.toString().trim()) }
        button("Clear", primary = false) { showReservationList(view, "") }
        work({ ReservationApi(current.server).list(view, search, current.token) }) { rows ->
            if (rows.length() == 0) {
                // Empty result is a normal outcome; transport failures surface via message.text in work().
                if (search.isNotBlank()) label("No matching bookings.", 14f, color(R.color.solar_muted_green))
                else label("No reservations in this view.", 14f, color(R.color.solar_muted_green))
            }
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val start = runCatching { Instant.parse(r.getString("startsAtUtc")) }.getOrNull()
                    ?.atZone(ZoneId.systemDefault())?.format(dateTimeFormat) ?: r.optString("startsAtUtc")
                val caption = if (isOperator)
                    "${r.getString("status")} - ${r.optString("prosumerNic")} - ${r.getString("direction")} - ${r.opt("requestedKwh")} kWh - $start"
                else
                    "${r.getString("status")} - ${r.getString("direction")} - $start"
                button(caption, primary = false) { showReservationDetail(r.getString("id")) }
            }
        }
        button("Back", primary = false) { showHome() }
    }
    /** Confirmation shown after create/update/cancel succeeds; [action] is requested, updated or cancelled. */
    private fun showReservationSummary(action: String, reservation: JSONObject, returnView: String) {
        val current = session ?: return showLogin()
        val heading = when (action) {
            "requested" -> "Reservation requested"
            "updated" -> "Reservation updated"
            "cancelled" -> "Reservation cancelled"
            else -> "Reservation update"
        }
        page(heading, "Your booking change was saved.")
        reservationView = returnView
        val reservationId = reservation.optString("id")
        val direction = reservation.optString("direction")
        val directionLabel = when (direction) {
            "CHARGING" -> "Charging"
            "DROP_OFF" -> "Drop-off"
            else -> direction
        }
        val status = reservation.optString("status")
        val statusLabel = when (status) {
            "PENDING" -> "Pending approval"
            "APPROVED" -> "Approved"
            "COMPLETED" -> "Completed"
            "CANCELLED" -> "Cancelled"
            "REJECTED" -> "Rejected"
            else -> status
        }
        val start = runCatching { Instant.parse(reservation.getString("startsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(reservation.optString("startsAtUtc"))
        val end = runCatching { Instant.parse(reservation.getString("endsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(reservation.optString("endsAtUtc"))
        statusPill(status.ifBlank { "PENDING" })
        label("Reference: $reservationId")
        val nodeLabel = label("Node: ${reservation.optString("nodeId")}")
        val slotLabel = label("Slot: ${reservation.optString("slotId")}")
        label("Time: $start – $end")
        label("Energy: ${reservation.opt("requestedKwh")} kWh — $directionLabel")
        label("Status: $statusLabel")
        button("View booking") { showReservationDetail(reservationId) }
        button("Back to bookings", primary = false) { showReservationList(returnView, reservationSearch) }
        // Names are optional enrichment: keep the saved result and navigation usable while loading.
        work({ NodeApi(current.server).list(current.token) }, blocking = false) { nodes ->
            var nodeName = reservation.optString("nodeId")
            var slotName = reservation.optString("slotId")
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                if (node.optString("id") == reservation.optString("nodeId")) {
                    nodeName = node.optString("name", nodeName)
                    val slots = node.optJSONArray("batterySlots")
                    if (slots != null) {
                        for (j in 0 until slots.length()) {
                            val slot = slots.getJSONObject(j)
                            if (slot.optString("id") == reservation.optString("slotId")) slotName = slot.optString("name", slotName)
                        }
                    }
                }
            }
            nodeLabel.text = "Node: $nodeName"
            slotLabel.text = "Slot: $slotName"
        }
    }
    private fun showReservationDetail(id: String) {
        val current = session ?: return showLogin()
        page("Reservation", "Details for this booking.")
        work({ ReservationApi(current.server).get(id, current.token) }) { r ->
            val status = r.getString("status")
            val isOperator = current.user.optString("role") == "GRID_OPERATOR"
            statusPill(status)
            val nic = r.optString("prosumerNic")
            if (nic.isNotBlank()) label("Prosumer: $nic")
            label("Node: ${r.optString("nodeId")}    Slot: ${r.optString("slotId")}")
            label("Direction: ${r.getString("direction")}    Requested: ${r.get("requestedKwh")} kWh")
            val start = runCatching { Instant.parse(r.getString("startsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }.getOrDefault(r.optString("startsAtUtc"))
            val end = runCatching { Instant.parse(r.getString("endsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }.getOrDefault(r.optString("endsAtUtc"))
            label("Start: $start")
            label("End: $end")
            val qrToken = if (r.isNull("qrToken")) null else r.optString("qrToken")
            if (status == "APPROVED" && !qrToken.isNullOrBlank()) {
                label("Show this code at the node to complete your transaction.", 15f, color(R.color.solar_muted_green))
                LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    background = roundedDrawable(color(R.color.solar_surface), color(R.color.solar_border), radiusDp = 16)
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    addView(ImageView(this@MainActivity).apply {
                        val size = dp(220)
                        setImageBitmap(encodeQrBitmap(qrToken))
                        contentDescription = "Reservation QR code"
                        layoutParams = LinearLayout.LayoutParams(size, size)
                    })
                    content.addView(this, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL })
                }
            }
            if (!isOperator && (status == "PENDING" || status == "APPROVED")) {
                button("Modify") {
                    editingReservationId = id
                    draftNode = JSONObject().put("id", r.optString("nodeId")).put("name", r.optString("nodeId"))
                    draftSlot = JSONObject().put("id", r.optString("slotId")).put("name", r.optString("slotId")).put("capacityKwh", 0)
                    draftStart = runCatching { Instant.parse(r.getString("startsAtUtc")).atZone(ZoneId.systemDefault()) }.getOrNull()
                    draftEnd = runCatching { Instant.parse(r.getString("endsAtUtc")).atZone(ZoneId.systemDefault()) }.getOrNull()
                    showReservationForm()
                }
                button("Cancel reservation", primary = false) {
                    AlertDialog.Builder(this@MainActivity).setTitle("Cancel this reservation?")
                        .setMessage("This cannot be undone.")
                        .setNegativeButton("Keep it", null).setPositiveButton("Cancel reservation") { _, _ ->
                            work({ ReservationApi(current.server).cancel(id, current.token) }) { cancelled ->
                                showReservationSummary("cancelled", cancelled, "history")
                            }
                        }.show()
                }
            } else if (isOperator && status == "PENDING") {
                button("Approve") { work({ ReservationApi(current.server).decide(id, "APPROVE", current.token) }) { showReservationDetail(id) } }
                button("Reject", primary = false) { work({ ReservationApi(current.server).decide(id, "REJECT", current.token) }) { showReservationDetail(id) } }
            } else if (isOperator) {
                label("This booking is read-only.", 14f, color(R.color.solar_muted_green))
            }
            button("Refresh") { showReservationDetail(id) }
        }
        button("Back", primary = false) { showReservationList(reservationView, reservationSearch) }
    }
    // ---- Grid Operator reservation flow ----
    private fun showPendingApprovals() {
        val current = session ?: return showLogin()
        page("Pending approvals", "Reservations awaiting a decision.")
        work({ ReservationApi(current.server).list("pending", token = current.token) }) { rows ->
            if (rows.length() == 0) label("No pending reservations.")
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                button("${r.optString("prosumerNic")} - ${r.getString("direction")} - ${r.get("requestedKwh")} kWh", primary = false) {
                    showApprovalDetail(r.getString("id"))
                }
            }
        }
        button("Back", primary = false) { showHome() }
    }
    private fun showApprovalDetail(id: String) {
        val current = session ?: return showLogin()
        page("Reservation request", "Approve or reject this booking.")
        work({ ReservationApi(current.server).get(id, current.token) }) { r ->
            label("Prosumer NIC: ${r.optString("prosumerNic")}")
            label("Node: ${r.optString("nodeId")}    Slot: ${r.optString("slotId")}")
            label("Direction: ${r.getString("direction")}    Requested: ${r.get("requestedKwh")} kWh")
            val start = runCatching { Instant.parse(r.getString("startsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }.getOrDefault(r.optString("startsAtUtc"))
            val end = runCatching { Instant.parse(r.getString("endsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }.getOrDefault(r.optString("endsAtUtc"))
            label("Start: $start")
            label("End: $end")
            statusPill(r.getString("status"))
            if (r.getString("status") == "PENDING") {
                button("Approve") { work({ ReservationApi(current.server).decide(id, "APPROVE", current.token) }) { showPendingApprovals() } }
                button("Reject", primary = false) { work({ ReservationApi(current.server).decide(id, "REJECT", current.token) }) { showPendingApprovals() } }
            }
        }
        button("Back", primary = false) { showPendingApprovals() }
    }
    private fun showCompleteTransaction() {
        val current = session ?: return showLogin()
        page("Complete a transaction", "Scan the prosumer's transaction QR code, or enter the token manually.")
        val token = field("QR token")
        scannedTokenField = token
        button("Scan QR code") {
            IntentIntegrator(this).apply {
                setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                setPrompt("Point the camera at the prosumer's transaction QR code")
                setBeepEnabled(true)
                setOrientationLocked(true)
            }.initiateScan()
        }
        button("Complete") {
            val value = token.text.toString().trim()
            if (value.isBlank()) { message.text = "Scan or enter a QR token."; return@button }
            work({ ReservationApi(current.server).complete(value, current.token) }) { r ->
                token.setText(""); message.text = "Transaction completed. Status: ${r.optString("status")}"
            }
        }
        button("Back", primary = false) { showHome() }
    }
    private fun <T> work(task: () -> T, blocking: Boolean = true, success: (T) -> Unit) {
        if (blocking && busy) return
        val generation = pageGeneration
        if (blocking) {
            busy = true; message.text = "Please wait..."; controls.forEach { it.isEnabled = false }
        }
        (if (blocking) worker else lookupWorker).execute {
            val result = runCatching(task)
            runOnUiThread {
                // A response belongs only to the page that requested it, including error responses.
                if (isDestroyed || isFinishing || generation != pageGeneration) return@runOnUiThread
                if (blocking) {
                    busy = false; controls.forEach { it.isEnabled = true }; message.text = ""
                }
                result.fold(success) { error ->
                    if (error is ApiFailure && error.status == 401) {
                        runCatching { SessionStore(this).use { it.clear() } }
                        session = null; showLogin()
                    } else if (!blocking) {
                        message.text = "Booking saved. Node names could not be loaded; IDs are shown instead."
                        return@fold
                    }
                    message.text = when (error) {
                        is ApiFailure, is IllegalArgumentException -> error.message
                        else -> "Unable to complete the request. Check your connection and service address, then retry."
                    }
                }
            }
        }
    }
}
