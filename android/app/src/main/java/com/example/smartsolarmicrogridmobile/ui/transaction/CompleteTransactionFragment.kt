package com.example.smartsolarmicrogridmobile.ui.transaction

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
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ReservationApi
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CompleteTransactionFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var tokenInput: EditText
    private lateinit var messageText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_complete_transaction, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tokenInput = view.findViewById(R.id.tokenInput)
        messageText = view.findViewById(R.id.messageText)

        view.findViewById<View>(R.id.scanButton).setOnClickListener {
            IntentIntegrator(requireActivity()).apply {
                setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                setPrompt("Point the camera at the prosumer's transaction QR code")
                setBeepEnabled(true)
                setOrientationLocked(true)
            }.initiateScan()
        }

        view.findViewById<View>(R.id.completeButton).setOnClickListener { complete() }

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_completeTransaction_to_home)
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg -> messageText.text = msg.orEmpty() }
    }

    private fun complete() {
        val session = sessionVm.session.value ?: return
        val value = tokenInput.text.toString().trim()
        if (value.isBlank()) { sessionVm.setMessage("Scan or enter a QR token."); return }

        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReservationApi(session.server).complete(value, session.token) }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = { r ->
                    tokenInput.setText("")
                    sessionVm.setMessage("Transaction completed. Status: ${r.optString("status")}")
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        val text = result.contents ?: return
        tokenInput.setText(text)
    }
}
