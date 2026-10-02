package com.example.smartsolarmicrogridmobile.ui.reservation

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
import com.example.smartsolarmicrogridmobile.NodeApi
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.addStatusPill
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.color
import com.example.smartsolarmicrogridmobile.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReservationSummaryFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var titleText: TextView
    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_reservation_summary, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        titleText = view.findViewById(R.id.titleText)

        val action = arguments?.getString("action") ?: "update"
        val reservationJson = arguments?.getString("reservation") ?: "{}"
        val returnView = arguments?.getString("returnView") ?: "current"

        val heading = when (action) {
            "requested" -> "Reservation requested"
            "updated" -> "Reservation updated"
            "cancelled" -> "Reservation cancelled"
            else -> "Reservation update"
        }
        titleText.text = heading

        val reservation = JSONObject(reservationJson)
        contentLayout.removeAllViews()

        val direction = reservation.optString("direction")
        val directionLabel = when (direction) {
            "CHARGING" -> "Charging"
            "DROP_OFF" -> "Drop-off"
            else -> direction
        }
        val status = reservation.optString("status")
        val statusLabel = when (status) {
            "PENDING" -> "Pending approval"
            "APPROVED" -> "Approved"
            "COMPLETED" -> "Completed"
            "CANCELLED" -> "Cancelled"
            "REJECTED" -> "Rejected"
            else -> status
        }
        val start = runCatching { Instant.parse(reservation.getString("startsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(reservation.optString("startsAtUtc"))
        val end = runCatching { Instant.parse(reservation.getString("endsAtUtc")).atZone(ZoneId.systemDefault()).format(dateTimeFormat) }
            .getOrDefault(reservation.optString("endsAtUtc"))

        contentLayout.addStatusPill(status.ifBlank { "PENDING" })
        contentLayout.label("Reference: ${reservation.optString("id")}")
        val nodeLabel = contentLayout.label("Node: ${reservation.optString("nodeId")}")
        val slotLabel = contentLayout.label("Slot: ${reservation.optString("slotId")}")
        contentLayout.label("Time: $start – $end")
        contentLayout.label("Energy: ${reservation.opt("requestedKwh")} kWh — $directionLabel")
        contentLayout.label("Status: $statusLabel")

        if (status == "PENDING" || status == "APPROVED")
            contentLayout.label("You can change or cancel this booking up to 12 hours before it starts.", 14f, requireContext().color(R.color.solar_muted_green))
        else if (status == "CANCELLED")
            contentLayout.label("This booking is cancelled and can no longer be changed.", 14f, requireContext().color(R.color.solar_muted_green))
        contentLayout.button("View booking") {
            val args = Bundle().apply { putString("reservationId", reservation.optString("id")) }
            findNavController().navigate(R.id.action_reservationSummary_to_reservationDetail, args)
        }
        contentLayout.button("Back to bookings", primary = false) {
            val args = Bundle().apply { putString("view", returnView); putString("search", "") }
            findNavController().navigate(R.id.action_reservationSummary_to_reservationList, args)
        }

        // Enrich with node/slot names
        val session = sessionVm.session.value ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                runCatching { NodeApi(session.server).list(session.token) }.getOrNull()
            } ?: return@launch
            var nodeName = reservation.optString("nodeId")
            var slotName = reservation.optString("slotId")
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                if (node.optString("id") == reservation.optString("nodeId")) {
                    nodeName = node.optString("name", nodeName)
                    val slots = node.optJSONArray("batterySlots")
                    if (slots != null) {
                        for (j in 0 until slots.length()) {
                            val slot = slots.getJSONObject(j)
                            if (slot.optString("id") == reservation.optString("slotId")) {
                                slotName = slot.optString("name", slotName)
                            }
                        }
                    }
                }
            }
            nodeLabel.text = "Node: $nodeName"
            slotLabel.text = "Slot: $slotName"
        }
    }
}
