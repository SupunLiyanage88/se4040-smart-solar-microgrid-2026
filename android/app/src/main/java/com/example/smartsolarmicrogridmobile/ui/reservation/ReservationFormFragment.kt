package com.example.smartsolarmicrogridmobile.ui.reservation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ReservationApi
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.label
import com.example.smartsolarmicrogridmobile.ui.sectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ReservationFormFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var messageText: TextView
    private lateinit var directionGroup: RadioGroup
    private lateinit var kwhInput: EditText
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var submitButton: Button

    private var draftStart: ZonedDateTime? = null
    private var draftEnd: ZonedDateTime? = null
    private var editingReservationId: String? = null

    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_reservation_form, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        titleText = view.findViewById(R.id.titleText)
        messageText = view.findViewById(R.id.messageText)
        directionGroup = view.findViewById(R.id.directionGroup)
        kwhInput = view.findViewById(R.id.kwhInput)
        startButton = view.findViewById(R.id.startButton)
        endButton = view.findViewById(R.id.endButton)
        submitButton = view.findViewById(R.id.submitButton)

        val nodeJson = arguments?.getString("node")
        val slotJson = arguments?.getString("slot")
        editingReservationId = arguments?.getString("reservationId")

        val editing = editingReservationId != null
        titleText.text = if (editing) "Modify reservation" else "New reservation"

        contentLayout.removeAllViews()

        if (nodeJson != null || slotJson != null) {
            contentLayout.sectionTitle("Location")
            nodeJson?.let { contentLayout.label("Node: ${JSONObject(it).optString("name")}") }
            slotJson?.let { contentLayout.label("Slot: ${JSONObject(it).optString("name")} (${JSONObject(it).opt("capacityKwh")} kWh capacity)") }
        }

        contentLayout.sectionTitle("Details")
        directionGroup.check(R.id.directionDropOff)

        contentLayout.sectionTitle("Schedule")
        startButton.setOnClickListener {
            pickDateTime(draftStart) { picked -> draftStart = picked; updateButtons() }
        }
        endButton.setOnClickListener {
            pickDateTime(draftEnd) { picked -> draftEnd = picked; updateButtons() }
        }
        updateButtons()

        submitButton.text = if (editing) "Save changes" else "Reserve"
        submitButton.setOnClickListener { submit() }

        view.findViewById<View>(R.id.cancelButton).setOnClickListener {
            if (editing) {
                findNavController().navigate(R.id.action_reservationForm_to_reservationList)
            } else {
                findNavController().navigate(R.id.action_reservationForm_to_home)
            }
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg -> messageText.text = msg.orEmpty() }
    }

    private fun updateButtons() {
        startButton.text = "Start: ${draftStart?.format(dateTimeFormat) ?: "Choose"}"
        endButton.text = "End: ${draftEnd?.format(dateTimeFormat) ?: "Choose"}"
    }

    private fun pickDateTime(initial: ZonedDateTime?, onPicked: (ZonedDateTime) -> Unit) {
        val base = initial ?: ZonedDateTime.now(ZoneId.systemDefault()).plusHours(1)
        DatePickerDialog(requireContext(), { _, y, m, d ->
            TimePickerDialog(requireContext(), { _, h, min ->
                onPicked(ZonedDateTime.of(y, m + 1, d, h, min, 0, 0, ZoneId.systemDefault()))
            }, base.hour, base.minute, false).show()
        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
    }

    private fun selectedDirection(): String {
        return if (directionGroup.checkedRadioButtonId == R.id.directionCharging) "CHARGING" else "DROP_OFF"
    }

    private fun submit() {
        val session = sessionVm.session.value ?: return
        val start = draftStart; val end = draftEnd
        if (start == null || end == null) { sessionVm.setMessage("Choose a start and end time."); return }
        if (end <= start) { sessionVm.setMessage("End must be after start."); return }
        val kwhValue = kwhInput.text.toString().toDoubleOrNull()
        if (kwhValue == null || kwhValue <= 0) { sessionVm.setMessage("Enter the requested kWh."); return }

        val nodeJson = arguments?.getString("node")
        val slotJson = arguments?.getString("slot")
        val body = JSONObject()
            .put("nodeId", if (nodeJson != null) JSONObject(nodeJson).optString("id") else "")
            .put("slotId", if (slotJson != null) JSONObject(slotJson).optString("id") else "")
            .put("direction", selectedDirection())
            .put("requestedKwh", kwhValue)
            .put("startsAtUtc", start.toInstant().toString())
            .put("endsAtUtc", end.toInstant().toString())

        val id = editingReservationId
        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (id != null) ReservationApi(session.server).update(id, body, session.token)
                    else ReservationApi(session.server).create(body, session.token)
                }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = { reservation ->
                    sessionVm.clearMessage()
                    val args = Bundle().apply {
                        putString("action", if (id != null) "updated" else "requested")
                        putString("reservation", reservation.toString())
                        putString("returnView", if (id != null) "current" else "pending")
                    }
                    findNavController().navigate(R.id.action_reservationForm_to_reservationSummary, args)
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }
}
