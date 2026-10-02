package com.example.smartsolarmicrogridmobile.ui.reservation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.example.smartsolarmicrogridmobile.ui.dp
import com.example.smartsolarmicrogridmobile.ui.label
import com.example.smartsolarmicrogridmobile.ui.roundedDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReservationListFragment : Fragment() {

    private companion object {
        const val ROW_TAG = "reservation-row"
    }

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var messageText: TextView
    private lateinit var searchInput: EditText
    private lateinit var tabLayout: LinearLayout

    private var currentView: String = "current"
    private var currentSearch: String = ""

    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_reservation_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        titleText = view.findViewById(R.id.titleText)
        messageText = view.findViewById(R.id.messageText)
        searchInput = view.findViewById(R.id.searchInput)
        tabLayout = view.findViewById(R.id.tabLayout)

        currentView = arguments?.getString("view") ?: "current"
        currentSearch = arguments?.getString("search") ?: ""
        searchInput.setText(currentSearch)

        val session = sessionVm.session.value ?: return
        val isOperator = session.user.optString("role") == "GRID_OPERATOR"
        titleText.text = if (isOperator) "Bookings" else "My reservations"

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_reservationList_to_home)
        }
        view.findViewById<View>(R.id.searchButton).setOnClickListener {
            currentSearch = searchInput.text.toString().trim()
            loadList()
        }
        view.findViewById<View>(R.id.clearButton).setOnClickListener {
            currentSearch = ""
            searchInput.setText("")
            loadList()
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg -> messageText.text = msg.orEmpty() }

        renderTabs()
        loadList()
    }

    private fun renderTabs() {
        tabLayout.removeAllViews()
        val ctx = requireContext()
        val tabs = listOf("current" to "Current", "pending" to "Pending", "history" to "History")
        for ((key, title) in tabs) {
            val active = key == currentView
            val tab = Button(ctx).apply {
                text = title; isAllCaps = false; textSize = 13f
                typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setTextColor(if (active) ctx.color(R.color.white) else ctx.color(R.color.solar_body_text))
                background = ctx.roundedDrawable(
                    if (active) ctx.color(R.color.solar_green) else ctx.color(R.color.solar_surface),
                    if (active) null else ctx.color(R.color.solar_border), radiusDp = 10
                )
                stateListAnimator = null
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                setOnClickListener {
                    currentView = key
                    renderTabs()
                    loadList()
                }
            }
            tabLayout.addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = if (key != "history") ctx.dp(8) else 0
            })
        }
    }

    private fun loadList() {
        val session = sessionVm.session.value ?: return
        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReservationApi(session.server).list(currentView, currentSearch, session.token) }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = { rows ->
                    sessionVm.clearMessage()
                    // Remove the rows from the previous load; the form fields above them stay in place.
                    for (index in contentLayout.childCount - 1 downTo 0) {
                        if (contentLayout.getChildAt(index).tag == ROW_TAG) contentLayout.removeViewAt(index)
                    }
                    val isOperator = session.user.optString("role") == "GRID_OPERATOR"
                    if (rows.length() == 0) {
                        val empty = if (currentSearch.isNotBlank()) contentLayout.label("No matching bookings.", 14f, requireContext().color(R.color.solar_muted_green))
                        else contentLayout.label("No reservations in this view.", 14f, requireContext().color(R.color.solar_muted_green))
                        empty.tag = ROW_TAG
                    }
                    for (i in 0 until rows.length()) {
                        val r = rows.getJSONObject(i)
                        val start = runCatching { Instant.parse(r.getString("startsAtUtc")) }
                            .getOrNull()?.atZone(ZoneId.systemDefault())?.format(dateTimeFormat) ?: r.optString("startsAtUtc")
                        val caption = if (isOperator)
                            "${r.getString("status")} - ${r.optString("prosumerNic")} - ${r.getString("direction")} - ${r.opt("requestedKwh")} kWh - $start"
                        else
                            "${r.getString("status")} - ${r.getString("direction")} - $start"
                        contentLayout.button(caption, primary = false) {
                            val args = Bundle().apply { putString("reservationId", r.getString("id")) }
                            findNavController().navigate(R.id.action_reservationList_to_reservationDetail, args)
                        }.tag = ROW_TAG
                    }
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }
}
