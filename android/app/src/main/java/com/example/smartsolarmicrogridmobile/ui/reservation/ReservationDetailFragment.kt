package com.example.smartsolarmicrogridmobile.ui.reservation

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.smartsolarmicrogridmobile.AccountSession
import com.example.smartsolarmicrogridmobile.NodeApi
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ReservationApi
import com.example.smartsolarmicrogridmobile.encodeQrBitmap
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.addStatusPill
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.color
import com.example.smartsolarmicrogridmobile.ui.dp
import com.example.smartsolarmicrogridmobile.ui.label
import com.example.smartsolarmicrogridmobile.ui.roundedDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReservationDetailFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var messageText: TextView
    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_reservation_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        messageText = view.findViewById(R.id.messageText)

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_reservationDetail_to_reservationList)
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
        val ctx = requireContext()
        contentLayout.removeAllViews()

        val status = r.getString("status")
        val isOperator = session.user.optString("role") == "GRID_OPERATOR"

        contentLayout.addStatusPill(status)

        val nic = r.optString("prosumerNic")
        if (nic.isNotBlank()) contentLayout.label("Prosumer: $nic")
        val nodeLabel = contentLayout.label("Node: ${r.optString("nodeId")}    Slot: ${r.optString("slotId")}")
        // Resolve readable names; the edit form also needs the real node and slot (name, capacity).
        var nodeJson = JSONObject().put("id", r.optString("nodeId")).put("name", r.optString("nodeId"))
        var slotJson = JSONObject().put("id", r.optString("slotId")).put("name", r.optString("slotId")).put("capacityKwh", 0)
        viewLifecycleOwner.lifecycleScope.launch {
            val node = withContext(Dispatchers.IO) {
                runCatching { NodeApi(session.server).get(r.optString("nodeId"), session.token) }.getOrNull()
            } ?: return@launch
            nodeJson = node
            val slots = node.optJSONArray("batterySlots")
            var slotName = r.optString("slotId")
            for (j in 0 until (slots?.length() ?: 0)) {
                val slot = slots!!.getJSONObject(j)
                if (slot.optString("id") == r.optString("slotId")) { slotJson = slot; slotName = slot.optString("name", slotName) }
            }
            nodeLabel.text = "Node: ${node.optString("name", r.optString("nodeId"))}    Slot: $slotName"
        }
        contentLayout.label("Direction: ${r.getString("direction")}    Requested: ${r.get("requestedKwh")} kWh")

        val start = runCatching { Instant.parse(r.getString("startsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(r.optString("startsAtUtc"))
        val end = runCatching { Instant.parse(r.getString("endsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(r.optString("endsAtUtc"))
        contentLayout.label("Start: $start")
        contentLayout.label("End: $end")

        val qrToken = if (r.isNull("qrToken")) null else r.optString("qrToken")
        if (status == "APPROVED" && !qrToken.isNullOrBlank()) {
            contentLayout.label("Show this code at the node to complete your transaction.", 15f, ctx.color(R.color.solar_muted_green))
            val qrContainer = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                background = ctx.roundedDrawable(ctx.color(R.color.solar_surface), ctx.color(R.color.solar_border), radiusDp = 16)
                setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            }
            val size = ctx.dp(220)
            qrContainer.addView(ImageView(ctx).apply {
                setImageBitmap(encodeQrBitmap(qrToken))
                contentDescription = "Reservation QR code"
                layoutParams = LinearLayout.LayoutParams(size, size)
            })
            contentLayout.addView(qrContainer, LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = ctx.dp(16); gravity = Gravity.CENTER_HORIZONTAL
            })
        }

        if (!isOperator && (status == "PENDING" || status == "APPROVED")) {
            contentLayout.label("Changes and cancellations need at least 12 hours' notice before the start time.", 14f, ctx.color(R.color.solar_muted_green))
            contentLayout.button("Modify") {
                val args = Bundle().apply {
                    putString("reservationId", r.getString("id"))
                    putString("node", nodeJson.toString())
                    putString("slot", slotJson.toString())
                    putString("reservation", r.toString())
                }
                findNavController().navigate(R.id.action_reservationDetail_to_reservationForm, args)
            }
            contentLayout.button("Cancel reservation", primary = false) {
                AlertDialog.Builder(ctx).setTitle("Cancel this reservation?")
                    .setMessage("This cannot be undone. Cancellations need at least 12 hours' notice before the start time.")
                    .setNegativeButton("Keep it", null).setPositiveButton("Cancel reservation") { _, _ ->
                        sessionVm.setBusy(true)
                        viewLifecycleOwner.lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { ReservationApi(session.server).cancel(r.getString("id"), session.token) }
                            }
                            sessionVm.setBusy(false)
                            result.fold(
                                onSuccess = { cancelled ->
                                    val args = Bundle().apply {
                                        putString("action", "cancelled")
                                        putString("reservation", cancelled.toString())
                                        putString("returnView", "history")
                                    }
                                    findNavController().navigate(R.id.action_reservationDetail_to_reservationSummary, args)
                                },
                                onFailure = { sessionVm.setMessage(it.message) }
                            )
                        }
                    }.show()
            }
        } else if (isOperator && status == "PENDING") {
            contentLayout.button("Approve") {
                sessionVm.setBusy(true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ReservationApi(session.server).decide(r.getString("id"), "APPROVE", session.token) }
                    }
                    sessionVm.setBusy(false)
                    result.fold(
                        onSuccess = { loadDetail(r.getString("id")) },
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
                        onSuccess = { loadDetail(r.getString("id")) },
                        onFailure = { sessionVm.setMessage(it.message) }
                    )
                }
            }
        } else if (isOperator) {
            contentLayout.label("This booking is read-only.", 14f, ctx.color(R.color.solar_muted_green))
        }

        contentLayout.button("Refresh", primary = false) { loadDetail(r.getString("id")) }
    }
}
