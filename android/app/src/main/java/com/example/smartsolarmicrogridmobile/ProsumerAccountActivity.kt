package com.example.smartsolarmicrogridmobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

class ProsumerAccountActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var message: TextView
    private var busy = false
    private val controls = mutableListOf<Button>()
    private val server = BuildConfig.API_BASE_URL
    private val brandGreen = Color.rgb(0x22, 0x6D, 0x50)      // #226D50 - primary buttons
    private val brandGreenHover = Color.rgb(0x17, 0x4E, 0x39) // #174E39 - pressed state
    private val mutedGreen = Color.rgb(0x39, 0x78, 0x5E)      // #39785E - secondary labels
    private val brandGreenDark = Color.rgb(0x14, 0x3D, 0x30)  // #143D30 - header bar
    private val bodyBackground = Color.rgb(0xF4, 0xF7, 0xF4)  // #F4F7F4
    private val bodyText = Color.rgb(0x20, 0x35, 0x2C)        // #20352C
    private val errorRed = Color.rgb(0xA0, 0x28, 0x28)        // #A02828
    private var session: AccountSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
        worker.execute {
            val saved = runCatching { SessionStore(this).use { it.load() } }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing || saved == null) return@runOnUiThread
                session = saved
                showHome()
            }
        }
    }


    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun showRegister() {
        buildScreen("Create a prosumer account",
            "Your NIC identifies your account. Backoffice approval is required before your first sign-in.")

        val name = field("Full name")
        val nic = field("NIC (12 digits or 9 digits followed by V/X)")
        val email = field("Email address", email = true)
        val password = field("Password (8-72 characters)", password = true)
        val confirm = field("Confirm password", password = true)

        button("Register") {
            if (password.text.toString() != confirm.text.toString()) {
                message.text = "Passwords do not match."
                return@button
            }
            val body = JSONObject()
                .put("userName", name.text.toString().trim())
                .put("nic", nic.text.toString().trim())
                .put("email", email.text.toString().trim())
                .put("password", password.text.toString())
            register(body)
        }
        button("Back to sign in") { showLogin() }
    }

    private fun register(body: JSONObject) {
        if (busy) return
        busy = true
        message.text = "Please wait..."
        controls.forEach { it.isEnabled = false }
        worker.execute {
            val result = runCatching { AccountApi(server).request("/register", "POST", body) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                controls.forEach { it.isEnabled = true }
                result.fold(
                    onSuccess = {
                        message.text = "Registration received. Ask Backoffice to activate your account, then sign in."
                    },
                    onFailure = { error ->
                        message.text = when (error) {
                            is ApiFailure -> error.message
                            else -> "Unable to complete the request. Check your connection and service address."
                        }
                    }
                )
            }
        }
    }

    private fun buildScreen(title: String, description: String) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val headerPadding = (20 * resources.displayMetrics.density).toInt()
        TextView(this).apply {
            text = "SOLARGRID"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(brandGreenDark)
            setPadding(headerPadding, headerPadding, headerPadding, headerPadding)
            root.addView(this)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(bodyBackground)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        label(title, 24f)
        label(description, 15f, mutedGreen)
        message = label("", 15f, errorRed)
    }



    private fun label(text: String, size: Float = 16f, color: Int = bodyText): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, 12, 0, 16)
            content.addView(this)
        }
    }

    private fun field(hint: String, value: String = "", password: Boolean = false, email: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> InputType.TYPE_CLASS_TEXT
            }
            isSaveEnabled = false
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })
        }
    }

    private fun button(title: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = title
            isAllCaps = false
            isEnabled = !busy
            setTextColor(Color.WHITE)
            setBackgroundColor(brandGreen)
            val padding = (14 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            controls.add(this)
        }
    }


    private fun showLogin() {
        buildScreen("Welcome back", "Sign in as a Prosumer.")

        val email = field("Email address", email = true)
        val password = field("Password", password = true)

        button("Sign in") {
            val credentials = JSONObject()
                .put("email", email.text.toString().trim())
                .put("password", password.text.toString())
            login(credentials)
        }
        button("Create a prosumer account") { showRegister() }
    }

    private fun login(credentials: JSONObject) {
        if (busy) return
        busy = true
        message.text = "Please wait..."
        controls.forEach { it.isEnabled = false }
        worker.execute {
            val result = runCatching { AccountApi(server).request("/login", "POST", credentials) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                controls.forEach { it.isEnabled = true }
                result.fold(
                    onSuccess = { response ->
                        val user = response.getJSONObject("user")
                        if (user.getString("role") != "PROSUMER") {
                            message.text = "This screen is for Prosumer accounts only."
                            return@fold
                        }
                        val newSession = AccountSession(server, response.getString("token"), response.getString("expiresAtUtc"), user)
                        session = newSession
                        runCatching { SessionStore(this).use { it.save(newSession) } }
                        showHome()

                    },
                    onFailure = { error ->
                        message.text = when (error) {
                            is ApiFailure -> error.message
                            else -> "Unable to complete the request. Check your connection and service address."
                        }
                    }
                )
            }
        }
    }
    private fun showHome() {
        val current = session ?: return showLogin()
        buildScreen("Welcome, ${current.user.getString("userName")}", "You are signed in as a Prosumer.")
        label("NIC: ${current.user.getString("nic")}")
        label(current.user.getString("email"))
        if (current.user.optBoolean("deactivationRequested")) {
            label("Your deactivation request is awaiting Backoffice review.", 15f, errorRed)
        }
        button("Edit my profile") { showProfile() }
        if (!current.user.optBoolean("deactivationRequested")) {
            button("Request deactivation") { requestDeactivation() }
        }
        button("Sign out") {
            session = null
            worker.execute { runCatching { SessionStore(this).use { it.clear() } } }
            showLogin()
        }

    }

    private fun requestDeactivation() {
        val current = session ?: return showLogin()
        if (busy) return
        busy = true
        message.text = "Please wait..."
        controls.forEach { it.isEnabled = false }
        worker.execute {
            val result = runCatching { AccountApi(current.server).request("/user/deactivation", "POST", token = current.token) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                controls.forEach { it.isEnabled = true }
                result.fold(
                    onSuccess = {
                        val updated = JSONObject(current.user.toString()).put("deactivationRequested", true)
                        session = current.copy(user = updated)
                        showHome()
                    },
                    onFailure = { error ->
                        message.text = when (error) {
                            is ApiFailure -> error.message
                            else -> "Unable to complete the request. Check your connection and service address."
                        }
                    }
                )
            }
        }
    }

    private fun showProfile() {
        val current = session ?: return showLogin()
        buildScreen("My profile", "Your NIC cannot be changed.")
        label("NIC: ${current.user.getString("nic")}")

        val name = field("Full name", value = current.user.getString("userName"))
        val email = field("Email address", value = current.user.getString("email"), email = true)

        button("Save profile") {
            val body = JSONObject()
                .put("userName", name.text.toString().trim())
                .put("email", email.text.toString().trim())
            updateProfile(body)
        }
        button("Cancel") { showHome() }
    }

    private fun updateProfile(body: JSONObject) {
        val current = session ?: return showLogin()
        if (busy) return
        busy = true
        message.text = "Please wait..."
        controls.forEach { it.isEnabled = false }
        worker.execute {
            val result = runCatching { AccountApi(current.server).request("/user", "PATCH", body, current.token) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                controls.forEach { it.isEnabled = true }
                result.fold(
                    onSuccess = { updatedUser ->
                        session = current.copy(user = updatedUser)
                        showHome()
                    },
                    onFailure = { error ->
                        message.text = when (error) {
                            is ApiFailure -> error.message
                            else -> "Unable to complete the request. Check your connection and service address."
                        }
                    }
                )
            }
        }
    }


}
