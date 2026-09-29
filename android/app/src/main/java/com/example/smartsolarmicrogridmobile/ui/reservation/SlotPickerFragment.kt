package com.example.smartsolarmicrogridmobile.ui.reservation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.color
import com.example.smartsolarmicrogridmobile.ui.label
import org.json.JSONObject

class SlotPickerFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var titleText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_slot_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        titleText = view.findViewById(R.id.titleText)

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_slotPicker_to_nodePicker)
        }

        val nodeJson = arguments?.getString("node") ?: run {
            findNavController().navigate(R.id.action_slotPicker_to_nodePicker)
            return
        }
        val node = JSONObject(nodeJson)
        titleText.text = node.getString("name")

        val slots = node.getJSONArray("batterySlots")
        if (slots.length() == 0) {
            contentLayout.label("This node has no battery slots configured.", 14f,
                requireContext().color(R.color.solar_muted_green))
        }
        for (i in 0 until slots.length()) {
            val slot = slots.getJSONObject(i)
            val available = slot.optBoolean("isAvailable", true)
            val caption = "${slot.getString("name")} - ${slot.get("capacityKwh")} kWh" + if (!available) " (unavailable)" else ""
            val b = contentLayout.button(caption, primary = false) {
                val args = Bundle().apply {
                    putString("node", nodeJson)
                    putString("slot", slot.toString())
                }
                findNavController().navigate(R.id.action_slotPicker_to_reservationForm, args)
            }
            if (!available) { b.isEnabled = false; b.setOnClickListener(null) }
        }
    }
}
