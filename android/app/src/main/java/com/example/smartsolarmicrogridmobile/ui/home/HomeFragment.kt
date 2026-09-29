package com.example.smartsolarmicrogridmobile.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.smartsolarmicrogridmobile.AccountApi
import com.example.smartsolarmicrogridmobile.AccountSession
import com.example.smartsolarmicrogridmobile.R
import com.example.smartsolarmicrogridmobile.ReservationApi
import com.example.smartsolarmicrogridmobile.SessionStore
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.addStatusPill
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.color
import com.example.smartsolarmicrogridmobile.ui.label
import com.example.smartsolarmicrogridmobile.ui.sectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        val titleText = view.findViewById<TextView>(R.id.titleText)

        sessionVm.session.observe(viewLifecycleOwner) { session ->
            if (session == null) {
                findNavController().navigate(R.id.action_home_to_login)
                return@observe
            }
            val user = session.user
            val prosumer = user.getString("role") == "PROSUMER"
            titleText.text = if (prosumer) "Prosumer home" else "Grid Operator home"
            renderHome(session, prosumer)
        }
    }

    private fun renderHome(session: AccountSession, prosumer: Boolean) {
        contentLayout.removeAllViews()
        val user = session.user
        val ctx = requireContext()

        contentLayout.label("Welcome, ${user.getString("userName")}", 15f, ctx.color(R.color.solar_muted_green))
        contentLayout.label("NIC: ${user.getString("nic")}")
        contentLayout.label(user.getString("email"))

        if (user.optBoolean("deactivationRequested")) {
            contentLayout.addStatusPill("PENDING")
            contentLayout.label("Your deactivation request is awaiting Backoffice review.", 14f, ctx.color(R.color.solar_muted_green))
        } else {
            contentLayout.addStatusPill("APPROVED")
        }

        contentLayout.button("Edit my profile") {
            findNavController().navigate(R.id.action_home_to_profile)
        }
        contentLayout.button("Refresh account", primary = false) {
            sessionVm.setBusy(true)
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val updated = AccountApi(session.server).request("/user", token = session.token)
                        session.copy(user = updated).also { s ->
                            SessionStore(ctx).use { it.save(s) }
                        }
                    }
                }
                sessionVm.setBusy(false)
                result.fold(
                    onSuccess = { sessionVm.setSession(it) },
                    onFailure = { sessionVm.setMessage(it.message) }
                )
            }
        }

        if (prosumer && !user.optBoolean("deactivationRequested")) {
            contentLayout.button("Request deactivation", primary = false) {
                AlertDialog.Builder(ctx).setTitle("Request account deactivation?")
                    .setMessage("Backoffice will review your request. Once deactivated, only Backoffice can reactivate your account.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Request") { _, _ ->
                        sessionVm.setBusy(true)
                        viewLifecycleOwner.lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    AccountApi(session.server).request("/user/deactivation", "POST", token = session.token)
                                    val updated = AccountApi(session.server).request("/user", token = session.token)
                                    session.copy(user = updated).also { s ->
                                        SessionStore(ctx).use { it.save(s) }
                                    }
                                }
                            }
                            sessionVm.setBusy(false)
                            result.fold(
                                onSuccess = { sessionVm.setSession(it) },
                                onFailure = { sessionVm.setMessage(it.message) }
                            )
                        }
                    }.show()
            }
        }

        contentLayout.sectionTitle("Reservations")

        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { ReservationApi(session.server).summary(session.token) }.getOrNull()
            }
            if (summary != null) {
                contentLayout.label(
                    "Pending: ${summary.optLong("pendingCount")}    Upcoming approved: ${summary.optLong("approvedFutureCount")}",
                    14f, ctx.color(R.color.solar_muted_green))
            }
        }

        if (prosumer) {
            contentLayout.button("Reserve a slot") {
                findNavController().navigate(R.id.action_home_to_nodePicker)
            }
            contentLayout.button("Find nodes nearby", primary = false) {
                val intent = android.content.Intent(ctx, com.example.smartsolarmicrogridmobile.NearbyNodesActivity::class.java)
                startActivityForResult(intent, 9001)
            }
            contentLayout.button("My reservations") {
                findNavController().navigate(
                    R.id.action_home_to_reservationList,
                    Bundle().apply { putString("view", "current"); putString("search", "") }
                )
            }
        } else {
            contentLayout.button("Pending approvals") {
                findNavController().navigate(R.id.action_home_to_pendingApprovals)
            }
            contentLayout.button("Bookings") {
                findNavController().navigate(
                    R.id.action_home_to_reservationList,
                    Bundle().apply { putString("view", "current"); putString("search", "") }
                )
            }
            contentLayout.button("Complete a transaction") {
                findNavController().navigate(R.id.action_home_to_completeTransaction)
            }
        }

        contentLayout.button("Sign out", primary = false) {
            sessionVm.signOut()
            // Navigation to login is handled by the session observer
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001 && resultCode == android.app.Activity.RESULT_OK) {
            data?.getStringExtra(com.example.smartsolarmicrogridmobile.NearbyNodesActivity.EXTRA_NODE_ID)?.let { nodeId ->
                findNavController().navigate(
                    R.id.action_home_to_nodePicker,
                    Bundle().apply { putString("nearbyNodeId", nodeId) }
                )
            }
        }
    }
}
