package com.example.smartsolarmicrogridmobile.ui.approval

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ReservationApi
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.color
import com.example.smartsolarmicrogridmobile.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PendingApprovalsFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var messageText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_pending_approvals, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        messageText = view.findViewById(R.id.messageText)

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_pendingApprovals_to_home)
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg -> messageText.text = msg.orEmpty() }
        loadApprovals()
    }

    private fun loadApprovals() {
        val session = sessionVm.session.value ?: return
        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReservationApi(session.server).list("pending", token = session.token) }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = { rows ->
                    sessionVm.clearMessage()
                    contentLayout.removeAllViews()
                    if (rows.length() == 0) {
                        contentLayout.label("No pending reservations.", 14f, requireContext().color(R.color.solar_muted_green))
                    }
                    for (i in 0 until rows.length()) {
                        val r = rows.getJSONObject(i)
                        contentLayout.button(
                            "${r.optString("prosumerNic")} - ${r.getString("direction")} - ${r.get("requestedKwh")} kWh",
                            primary = false
                        ) {
                            val args = Bundle().apply { putString("reservationId", r.getString("id")) }
                            findNavController().navigate(R.id.action_pendingApprovals_to_approvalDetail, args)
                        }
                    }
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }
}
