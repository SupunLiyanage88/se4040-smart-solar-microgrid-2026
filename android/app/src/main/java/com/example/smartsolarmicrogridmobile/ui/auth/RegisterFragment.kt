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
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RegisterFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()

    private lateinit var nameInput: EditText
    private lateinit var nicInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmInput: EditText
    private lateinit var messageText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_register, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        nameInput = view.findViewById(R.id.nameInput)
        nicInput = view.findViewById(R.id.nicInput)
        emailInput = view.findViewById(R.id.emailInput)
        passwordInput = view.findViewById(R.id.passwordInput)
        confirmInput = view.findViewById(R.id.confirmInput)
        messageText = view.findViewById(R.id.messageText)

        view.findViewById<Button>(R.id.registerButton).setOnClickListener { doRegister() }
        view.findViewById<Button>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_register_to_login)
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg ->
            messageText.text = msg.orEmpty()
        }
    }

    private fun doRegister() {
        if (passwordInput.text.toString() != confirmInput.text.toString()) {
            sessionVm.setMessage("Passwords do not match.")
            return
        }
        val body = JSONObject()
            .put("userName", nameInput.text.toString().trim())
            .put("nic", nicInput.text.toString().trim())
            .put("email", emailInput.text.toString().trim())
            .put("password", passwordInput.text.toString())

        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AccountApi(sessionVm.server).request("/register", "POST", body) }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = {
                    sessionVm.clearMessage()
                    passwordInput.setText("")
                    confirmInput.setText("")
                    findNavController().navigate(
                        R.id.action_register_to_login,
                        Bundle().apply { putString("info",
                            "Registration received. Ask Backoffice to activate your account, then sign in.") }
                    )
                },
                onFailure = { error ->
                    sessionVm.setMessage(error.message ?: "Unable to complete the request.")
                }
            )
        }
    }
}
