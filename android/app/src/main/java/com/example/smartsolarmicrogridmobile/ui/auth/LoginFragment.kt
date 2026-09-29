package com.example.smartsolarmicrogridmobile.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.smartsolarmicrogridmobile.AccountApi
import com.example.smartsolarmicrogridmobile.AccountSession
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.SessionStore
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LoginFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var messageText: TextView
    private lateinit var infoText: TextView
    private lateinit var signInButton: Button
    private lateinit var registerButton: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_login, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        emailInput = view.findViewById(R.id.emailInput)
        passwordInput = view.findViewById(R.id.passwordInput)
        messageText = view.findViewById(R.id.messageText)
        infoText = view.findViewById(R.id.infoText)
        signInButton = view.findViewById(R.id.signInButton)
        registerButton = view.findViewById(R.id.registerButton)

        val info = arguments?.getString("info").orEmpty()
        if (info.isNotBlank()) {
            infoText.text = info
            infoText.visibility = View.VISIBLE
        }

        signInButton.setOnClickListener { doSignIn() }
        registerButton.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_register)
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg ->
            messageText.text = msg.orEmpty()
        }
    }

    private fun doSignIn() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val response = AccountApi(sessionVm.server).request("/login", "POST",
                        JSONObject().put("email", email).put("password", password))
                    val user = response.getJSONObject("user")
                    if (user.getString("role") !in listOf("PROSUMER", "GRID_OPERATOR"))
                        throw IllegalStateException("Backoffice accounts use the web portal.")
                    AccountSession(sessionVm.server, response.getString("token"),
                        response.getString("expiresAtUtc"), user)
                        .also { current -> SessionStore(requireContext()).use { it.save(current) } }
                }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = { session ->
                    sessionVm.clearMessage()
                    sessionVm.setSession(session)
                    passwordInput.setText("")
                    // Navigation to home is handled by the observer in MainActivity
                },
                onFailure = { error ->
                    sessionVm.setMessage(error.message ?: "Unable to complete the request.")
                }
            )
        }
    }
}
