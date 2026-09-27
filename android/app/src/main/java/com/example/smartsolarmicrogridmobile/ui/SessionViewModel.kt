package com.example.smartsolarmicrogridmobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.smartsolarmicrogridmobile.AccountApi
import com.example.smartsolarmicrogridmobile.AccountSession
import com.example.smartsolarmicrogridmobile.ApiFailure
import com.example.smartsolarmicrogridmobile.BuildConfig
import com.example.smartsolarmicrogridmobile.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/**
 * Activity-scoped ViewModel that owns the authenticated session.
 * Fragments observe [session] to react to sign-in / sign-out.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val _session = MutableLiveData<AccountSession?>(null)
    val session: LiveData<AccountSession?> = _session

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _message = MutableLiveData<String?>(null)
    val message: LiveData<String?> = _message

    var server: String = BuildConfig.API_BASE_URL
        private set

    /** Called on app start: restore persisted session if still valid. */
    fun restoreSession() {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = SessionStore(getApplication()).use { it.load() } ?: return@runCatching null
                    if (Instant.parse(saved.expires).isBefore(Instant.now())) {
                        throw ApiFailure(401, "Your session expired. Please sign in again.")
                    }
                    val current = AccountApi(saved.server).request("/user", token = saved.token)
                    checkMobileRole(current)
                    saved.copy(user = current).also { updated ->
                        SessionStore(getApplication()).use { it.save(updated) }
                    }
                }.getOrNull()
            }
            _session.value = restored
            restored?.let { server = it.server }
        }
    }

    /** Called when returning to the app: revalidate the session against the server. */
    fun revalidate() {
        val current = _session.value ?: return
        viewModelScope.launch {
            val validated = withContext(Dispatchers.IO) {
                runCatching {
                    val user = AccountApi(current.server).request("/user", token = current.token)
                    checkMobileRole(user)
                    current.copy(user = user).also { updated ->
                        SessionStore(getApplication()).use { it.save(updated) }
                    }
                }.getOrNull()
            }
            _session.value = validated
        }
    }

    fun setSession(session: AccountSession) {
        server = session.server
        _session.value = session
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            SessionStore(getApplication()).use { it.clear() }
        }
        _session.value = null
    }

    fun setBusy(value: Boolean) { _busy.value = value }
    fun setMessage(msg: String?) { _message.value = msg }
    fun clearMessage() { _message.value = null }

    private fun checkMobileRole(user: JSONObject) {
        if (user.getString("role") !in listOf("PROSUMER", "GRID_OPERATOR"))
            throw ApiFailure(403, "Backoffice accounts use the web portal.")
    }
}
