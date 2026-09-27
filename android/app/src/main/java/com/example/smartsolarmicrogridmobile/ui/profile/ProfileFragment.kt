package com.example.smartsolarmicrogridmobile.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.smartsolarmicrogridmobile.AccountApi
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.SessionStore
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ProfileFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var messageText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val session = sessionVm.session.value ?: run {
            findNavController().navigate(R.id.action_profile_to_home)
            return
        }
        val user = session.user

        view.findViewById<TextView>(R.id.nicText).text = "NIC: ${user.getString("nic")}"
        nameInput = view.findViewById(R.id.nameInput)
        emailInput = view.findViewById(R.id.emailInput)
        messageText = view.findViewById(R.id.messageText)
        nameInput.setText(user.getString("userName"))
        emailInput.setText(user.getString("email"))

        view.findViewById<View>(R.id.saveButton).setOnClickListener { saveProfile() }
        view.findViewById<View>(R.id.cancelButton).setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_home)
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg -> messageText.text = msg.orEmpty() }
    }

    private fun saveProfile() {
        val session = sessionVm.session.value ?: return
        val body = JSONObject()
            .put("userName", nameInput.text.toString().trim())
            .put("email", emailInput.text.toString().trim())

        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val user = AccountApi(session.server).request("/user", "PATCH", body, session.token)
                    session.copy(user = user).also { updated ->
                        SessionStore(requireContext()).use { it.save(updated) }
                    }
                }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = {
                    sessionVm.clearMessage()
                    sessionVm.setSession(it)
                    findNavController().navigate(R.id.action_profile_to_home)
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }
}
