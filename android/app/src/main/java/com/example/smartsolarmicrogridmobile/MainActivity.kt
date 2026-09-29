package com.example.smartsolarmicrogridmobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.smartsolarmicrogridmobile.ui.SessionViewModel

/**
 * Single-activity host. All screens are Fragments navigated via the Navigation component.
 * Session state is managed by [SessionViewModel] scoped to this activity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sessionVm: SessionViewModel
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionVm = ViewModelProvider(this)[SessionViewModel::class.java]
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Restore session and navigate to home if valid
        sessionVm.restoreSession()
        sessionVm.session.observe(this) { session ->
            val currentDest = navController.currentDestination?.id
            if (session != null && currentDest == R.id.loginFragment) {
                navController.navigate(R.id.action_login_to_home)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Revalidate when returning to the app so remote deactivation is observed
        if (sessionVm.session.value != null) {
            sessionVm.revalidate()
        }
    }
}
