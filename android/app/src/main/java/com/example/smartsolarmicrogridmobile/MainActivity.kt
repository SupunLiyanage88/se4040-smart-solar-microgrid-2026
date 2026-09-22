package com.example.smartsolarmicrogridmobile

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Native Android account screens; all operations go through the REST API. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
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
    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

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
        if (current != null && !busy) work({ validated(current) }) { session = it; showHome() }
    }
    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
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
    private fun page(title: String, description: String) {
        controls.clear()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(244, 247, 244))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        label("SOLARGRID", 16f, Color.rgb(34, 109, 80))
        label(title, 28f)
        label(description, 16f)
        message = label("", 15f, Color.rgb(160, 40, 40)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    }
    private fun label(text: String, size: Float = 16f, color: Int = Color.rgb(32, 53, 44)): TextView {
        return TextView(this).apply { this.text = text; textSize = size; setTextColor(color); setPadding(0, 12, 0, 16); content.addView(this) }
    }
    private fun field(title: String, value: String = "", password: Boolean = false, email: Boolean = false, numeric: Boolean = false): EditText {
        val titleView = label(title, 14f)
        return EditText(this).apply {
            id = View.generateViewId(); titleView.labelFor = id
            setText(value); hint = title; setSingleLine(true)
            inputType = when { password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT }
            // Passwords and tokens must not be included in Android view-state snapshots.
            isSaveEnabled = false
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })
        }
    }
    private fun radioChoice(title: String, options: List<String>, selected: String = options.first()): RadioGroup {
        label(title, 14f)
        return RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            options.forEach { option ->
                addView(RadioButton(this@MainActivity).apply {
                    text = option; id = View.generateViewId(); isChecked = option == selected
                })
            }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })
        }
    }
    private fun RadioGroup.selectedText(): String = findViewById<RadioButton>(checkedRadioButtonId).text.toString()
    private fun button(title: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = title; isAllCaps = false; isEnabled = !busy
            setOnClickListener { action() }; content.addView(this); controls.add(this)
        }
    }
    private fun showLogin(info: String = "") {
        page("Welcome back", "Sign in as a Prosumer or Grid Operator.")
        if (info.isNotBlank()) label(info, 16f, Color.rgb(34, 109, 80))
        val address = field("Service address", server)
        val email = field("Email address", email = true)
        val password = field("Password", password = true)
        button("Sign in") {
            val endpoint = address.text.toString().trim().trimEnd('/')
            val credentials = JSONObject().put("email", email.text.toString().trim()).put("password", password.text.toString())
            work({
                val response = AccountApi(endpoint).request("/login", "POST", credentials)
                val user = response.getJSONObject("user"); checkMobileRole(user)
                AccountSession(endpoint, response.getString("token"), response.getString("expiresAtUtc"), user)
                    .also { current -> SessionStore(this).use { it.save(current) } }
            }) { current -> session = current; server = endpoint; password.setText(""); showHome() }
        }
        button("Create a prosumer account") { server = address.text.toString().trim().trimEnd('/'); showRegister() }
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
        button("Back to sign in") { showLogin() }
    }
    private fun showHome() {
        val current = session ?: return showLogin()
        val user = current.user
        val prosumer = user.getString("role") == "PROSUMER"
        page(if (prosumer) "Prosumer home" else "Grid Operator home", "Welcome, ${user.getString("userName")}")
        label("NIC: ${user.getString("nic")}")
        label(user.getString("email"))
        label("Account active", 16f, Color.rgb(34, 109, 80))
        if (user.optBoolean("deactivationRequested")) label("Your deactivation request is awaiting Backoffice review.")
        button("Edit my profile") { showProfile() }
        button("Refresh account") { work({ validated(current) }) { session = it; showHome() } }
        if (prosumer && !user.optBoolean("deactivationRequested")) button("Request deactivation") {
            AlertDialog.Builder(this).setTitle("Request account deactivation?")
                .setMessage("Backoffice will review your request. Once deactivated, only Backoffice can reactivate your account.")
                .setNegativeButton("Cancel", null).setPositiveButton("Request") { _, _ ->
                    work({
                        AccountApi(current.server).request("/user/deactivation", "POST", token = current.token)
                        validated(current)
                    }) { session = it; showHome() }
                }.show()
        }
        label("Reservations", 20f)
        work({ ReservationApi(current.server).summary(current.token) }) { summary ->
            label("Pending: ${summary.optLong("pendingCount")}    Upcoming approved: ${summary.optLong("approvedFutureCount")}")
        }
        if (prosumer) {
            button("Reserve a slot") { draftNode = null; draftSlot = null; editingReservationId = null; draftStart = null; draftEnd = null; showNodePicker() }
            button("My reservations") { showReservationList("current") }
        } else {
            button("Pending approvals") { showPendingApprovals() }
            button("Complete a transaction") { showCompleteTransaction() }
        }
        button("Sign out") { work({ SessionStore(this).use { it.clear() } }) { session = null; showLogin("You have signed out.") } }
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
        button("Cancel") { showHome() }
    }
    // ---- Prosumer reservation flow ----
    private fun showNodePicker() {
        val current = session ?: return showLogin()
        page("Choose a location", "Pick a grid node to reserve a battery slot.")
        work({ NodeApi(current.server).list(current.token) }) { nodes ->
            if (nodes.length() == 0) label("No active nodes are available right now.")
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                button("${node.getString("name")} - ${node.getString("address")}") { draftNode = node; showSlotPicker(node) }
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
            val b = button(caption) { draftSlot = slot; showReservationForm() }
            if (!available) { b.isEnabled = false; b.setOnClickListener(null) }
        }
        button("Back") { showNodePicker() }
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
        draftNode?.let { label("Node: ${it.optString("name")}") }
        draftSlot?.let { label("Slot: ${it.optString("name")} (${it.opt("capacityKwh")} kWh capacity)") }
        val direction = radioChoice("Direction", listOf("DROP_OFF", "CHARGING"))
        val kwh = field("Requested kWh", numeric = true)
        button("Start: ${draftStart?.format(dateTimeFormat) ?: "Choose"}") {
            pickDateTime(draftStart) { picked -> draftStart = picked; showReservationForm() }
        }
        button("End: ${draftEnd?.format(dateTimeFormat) ?: "Choose"}") {
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
            }) {
                editingReservationId = null; draftStart = null; draftEnd = null
                showReservationList(if (id != null) reservationView else "pending")
            }
        }
        button("Cancel") { if (editing) showReservationList(reservationView) else showHome() }
    }
    private fun showReservationList(view: String) {
        val current = session ?: return showLogin()
        reservationView = view
        page("My reservations", "Filter by status.")
        val labelFor = mapOf("current" to "Current", "pending" to "Pending", "history" to "History")
        labelFor.forEach { (key, title) -> button(title) { showReservationList(key) } }
        label("Showing: ${labelFor[view] ?: view}", 14f)
        work({ ReservationApi(current.server).list(view, token = current.token) }) { rows ->
            if (rows.length() == 0) label("No reservations in this view.")
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val start = runCatching { Instant.parse(r.getString("startsAtUtc")) }.getOrNull()
                    ?.atZone(ZoneId.systemDefault())?.format(dateTimeFormat) ?: r.optString("startsAtUtc")
                button("${r.getString("status")} - ${r.getString("direction")} - $start") { showReservationDetail(r.getString("id")) }
            }
        }
        button("Back") { showHome() }
    }
    private fun showReservationDetail(id: String) {
        val current = session ?: return showLogin()
        page("Reservation", "Details for this booking.")
        work({ ReservationApi(current.server).get(id, current.token) }) { r ->
            val status = r.getString("status")
            label("Status: $status")
            label("Node: ${r.optString("nodeId")}    Slot: ${r.optString("slotId")}")
            label("Direction: ${r.getString("direction")}    Requested: ${r.get("requestedKwh")} kWh")
            val start = runCatching { Instant.parse(r.getString("startsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }.getOrDefault(r.optString("startsAtUtc"))
            val end = runCatching { Instant.parse(r.getString("endsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }.getOrDefault(r.optString("endsAtUtc"))
            label("Start: $start")
            label("End: $end")
            val qrToken = if (r.isNull("qrToken")) null else r.optString("qrToken")
            if (status == "APPROVED" && !qrToken.isNullOrBlank()) {
                label("Show this code at the node to complete your transaction.", 15f, Color.rgb(34, 109, 80))
                ImageView(this).apply {
                    val size = (260 * resources.displayMetrics.density).toInt()
                    setImageBitmap(encodeQrBitmap(qrToken))
                    contentDescription = "Reservation QR code"
                    content.addView(this, LinearLayout.LayoutParams(size, size))
                }
            }
            if (status == "PENDING" || status == "APPROVED") {
                button("Modify") {
                    editingReservationId = id
                    draftNode = JSONObject().put("id", r.optString("nodeId")).put("name", r.optString("nodeId"))
                    draftSlot = JSONObject().put("id", r.optString("slotId")).put("name", r.optString("slotId")).put("capacityKwh", 0)
                    draftStart = runCatching { Instant.parse(r.getString("startsAtUtc")).atZone(ZoneId.systemDefault()) }.getOrNull()
                    draftEnd = runCatching { Instant.parse(r.getString("endsAtUtc")).atZone(ZoneId.systemDefault()) }.getOrNull()
                    showReservationForm()
                }
                button("Cancel reservation") {
                    AlertDialog.Builder(this@MainActivity).setTitle("Cancel this reservation?")
                        .setMessage("This cannot be undone.")
                        .setNegativeButton("Keep it", null).setPositiveButton("Cancel reservation") { _, _ ->
                            work({ ReservationApi(current.server).cancel(id, current.token) }) { showReservationList(reservationView) }
                        }.show()
                }
            }
            button("Refresh") { showReservationDetail(id) }
        }
        button("Back") { showReservationList(reservationView) }
    }
    // ---- Grid Operator reservation flow ----
    private fun showPendingApprovals() {
        val current = session ?: return showLogin()
        page("Pending approvals", "Reservations awaiting a decision.")
        work({ ReservationApi(current.server).list("pending", token = current.token) }) { rows ->
            if (rows.length() == 0) label("No pending reservations.")
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                button("${r.optString("prosumerNic")} - ${r.getString("direction")} - ${r.get("requestedKwh")} kWh") {
                    showApprovalDetail(r.getString("id"))
                }
            }
        }
        button("Back") { showHome() }
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
            label("Status: ${r.getString("status")}")
            if (r.getString("status") == "PENDING") {
                button("Approve") { work({ ReservationApi(current.server).decide(id, "APPROVE", current.token) }) { showPendingApprovals() } }
                button("Reject") { work({ ReservationApi(current.server).decide(id, "REJECT", current.token) }) { showPendingApprovals() } }
            }
        }
        button("Back") { showPendingApprovals() }
    }
    private fun showCompleteTransaction() {
        val current = session ?: return showLogin()
        page("Complete a transaction", "Enter the prosumer's transaction QR token to finalize the transfer.")
        val token = field("QR token")
        button("Complete") {
            val value = token.text.toString().trim()
            if (value.isBlank()) { message.text = "Enter a QR token."; return@button }
            work({ ReservationApi(current.server).complete(value, current.token) }) { r ->
                token.setText(""); message.text = "Transaction completed. Status: ${r.optString("status")}"
            }
        }
        button("Back") { showHome() }
    }
    private fun <T> work(task: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true; message.text = "Please wait..."; controls.forEach { it.isEnabled = false }
        worker.execute {
            val result = runCatching(task)
            val failure = result.exceptionOrNull()
            if (failure is ApiFailure && failure.status == 401) runCatching { SessionStore(this).use { it.clear() } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false; controls.forEach { it.isEnabled = true }; message.text = ""
                result.fold(success) { error ->
                    if (error is ApiFailure && error.status == 401) { session = null; showLogin() }
                    message.text = when (error) {
                        is ApiFailure, is IllegalArgumentException -> error.message
                        else -> "Unable to complete the request. Check your connection and service address, then retry."
                    }
                }
            }
        }
    }
}
