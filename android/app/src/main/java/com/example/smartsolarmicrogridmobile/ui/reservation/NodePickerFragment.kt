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
import com.example.smartsolarmicrogridmobile.SessionStore
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel
import com.example.smartsolarmicrogridmobile.ui.button
import com.example.smartsolarmicrogridmobile.ui.color
import com.example.smartsolarmicrogridmobile.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NodePickerFragment : Fragment() {

    private val sessionVm: SessionViewModel by activityViewModels()
    private lateinit var contentLayout: LinearLayout
    private lateinit var messageText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_node_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentLayout = view.findViewById(R.id.contentLayout)
        messageText = view.findViewById(R.id.messageText)

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigate(R.id.action_nodePicker_to_home)
        }

        sessionVm.message.observe(viewLifecycleOwner) { msg -> messageText.text = msg.orEmpty() }

        val nearbyNodeId = arguments?.getString("nearbyNodeId")
        if (nearbyNodeId != null) {
            openNodeFromNearby(nearbyNodeId)
        } else {
            loadNodes()
        }
    }

    private fun loadNodes() {
        val session = sessionVm.session.value ?: return
        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { NodeApi(session.server).list(session.token) }
                    .onSuccess { fresh -> runCatching { SessionStore(requireContext()).use { it.saveNodes(fresh) } } }
            }
            sessionVm.setBusy(false)
            // Offline fallback: show the last node list saved in SQLite, but only for connection failures.
            val cached = if (result.isFailure && (result.exceptionOrNull() !is com.example.smartsolarmicrogridmobile.ApiFailure))
                withContext(Dispatchers.IO) { runCatching { SessionStore(requireContext()).use { it.loadNodes() } }.getOrNull() }
                    ?.takeIf { it.length() > 0 } else null
            (if (cached != null) Result.success(cached) else result).fold(
                onSuccess = { nodes ->
                    if (cached != null) sessionVm.setMessage("Offline: showing the last saved node list. Reservations still need a connection.")
                    else sessionVm.clearMessage()
                    contentLayout.removeAllViews()
                    val ctx = requireContext()
                    if (nodes.length() == 0) {
                        contentLayout.label("No active nodes are available right now.", 14f, ctx.color(R.color.solar_muted_green))
                    }
                    for (i in 0 until nodes.length()) {
                        val node = nodes.getJSONObject(i)
                        contentLayout.button("${node.getString("name")} - ${node.getString("address")}", primary = false) {
                            val args = Bundle().apply { putString("node", node.toString()) }
                            findNavController().navigate(R.id.action_nodePicker_to_slotPicker, args)
                        }
                    }
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }

    private fun openNodeFromNearby(nodeId: String) {
        val session = sessionVm.session.value ?: return
        sessionVm.setBusy(true)
        sessionVm.setMessage("Please wait...")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { NodeApi(session.server).get(nodeId, session.token) }
            }
            sessionVm.setBusy(false)
            result.fold(
                onSuccess = { node ->
                    sessionVm.clearMessage()
                    val args = Bundle().apply { putString("node", node.toString()) }
                    findNavController().navigate(R.id.action_nodePicker_to_slotPicker, args)
                },
                onFailure = { sessionVm.setMessage(it.message) }
            )
        }
    }
}
