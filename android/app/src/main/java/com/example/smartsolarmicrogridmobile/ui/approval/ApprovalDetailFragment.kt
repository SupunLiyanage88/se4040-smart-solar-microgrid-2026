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
import com.example.smartsolarmicrogridmobile.AccountSession
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ReservationApi
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.addStatusPill
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ApprovalDetailFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var messageText: TextView
    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_approval_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        messageText = view.findViewById(R.id.messageText)

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_approvalDetail_to_pendingApprovals)
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg -> messageText.text = msg.orEmpty() }

        val reservationId = arguments?.getString("reservationId") ?: return
        loadDetail(reservationId)
    }

    private fun loadDetail(id: String) {
        val session = sessionVm.session.value ?: return
        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReservationApi(session.server).get(id, session.token) }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = { r ->
                    sessionVm.clearMessage()
                    renderDetail(r, session)
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }

    private fun renderDetail(r: JSONObject, session: AccountSession) {
        contentLayout.removeAllViews()

        contentLayout.label("Prosumer NIC: ${r.optString("prosumerNic")}")
        contentLayout.label("Node: ${r.optString("nodeId")}    Slot: ${r.optString("slotId")}")
        contentLayout.label("Direction: ${r.getString("direction")}    Requested: ${r.get("requestedKwh")} kWh")

        val start = runCatching { Instant.parse(r.getString("startsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(r.optString("startsAtUtc"))
        val end = runCatching { Instant.parse(r.getString("endsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(r.optString("endsAtUtc"))
        contentLayout.label("Start: $start")
        contentLayout.label("End: $end")

        contentLayout.addStatusPill(r.getString("status"))

        if (r.getString("status") == "PENDING") {
            contentLayout.button("Approve") {
                sessionVm.setBusy(true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ReservationApi(session.server).decide(r.getString("id"), "APPROVE", session.token) }
                    }
                    sessionVm.setBusy(false)
                    result.fold(
                        onSuccess = { findNavController().navigate(R.id.action_approvalDetail_to_pendingApprovals) },
                        onFailure = { sessionVm.setMessage(it.message) }
                    )
                }
            }
            contentLayout.button("Reject", primary = false) {
                sessionVm.setBusy(true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ReservationApi(session.server).decide(r.getString("id"), "REJECT", session.token) }
                    }
                    sessionVm.setBusy(false)
                    result.fold(
                        onSuccess = { findNavController().navigate(R.id.action_approvalDetail_to_pendingApprovals) },
                        onFailure = { sessionVm.setMessage(it.message) }
                    )
                }
            }
        }
    }
}
