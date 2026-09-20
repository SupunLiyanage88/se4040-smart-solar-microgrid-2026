package com.example.smartsolarmicrogridmobile

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.time.Instant
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
    private fun field(title: String, value: String = "", password: Boolean = false, email: Boolean = false): EditText {
        val titleView = label(title, 14f)
        return EditText(this).apply {
            id = View.generateViewId(); titleView.labelFor = id
            setText(value); hint = title; setSingleLine(true)
            inputType = when { password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> InputType.TYPE_CLASS_TEXT }
            // Passwords and tokens must not be included in Android view-state snapshots.
            isSaveEnabled = false
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })
        }
    }
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
