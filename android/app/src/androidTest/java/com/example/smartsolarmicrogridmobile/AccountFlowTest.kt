package com.example.smartsolarmicrogridmobile

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against the disposable account-check server when an API URL is supplied. */
@RunWith(AndroidJUnit4::class)
class AccountFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun awaitText(text: String) {
        var last: Throwable? = null
        repeat(60) {
            try { onView(withText(text)).check(matches(isDisplayed())); return }
            catch (failure: Throwable) { last = failure; Thread.sleep(250) }
        }
        throw AssertionError("UI did not display $text", last)
    }
    @Test fun loginEditRestartAndLogout() {
        val server = InstrumentationRegistry.getArguments().getString("apiUrl")
        assumeTrue("Supply apiUrl for the disposable test API", !server.isNullOrBlank())
        SessionStore(context).use { it.clear() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("Welcome back")
            onView(withHint("Service address")).perform(replaceText(server!!), closeSoftKeyboard())
            onView(withHint("Email address")).perform(replaceText("edited@example.test"), closeSoftKeyboard())
            onView(withHint("Password")).perform(replaceText("Synthetic-test-pass-42"), closeSoftKeyboard())
            onView(withText("Sign in")).perform(scrollTo(), click())
            awaitText("Prosumer home")
            val saved = SessionStore(context).use { it.load()!! }
            assertEquals("200000000002", saved.user.getString("nic"))
            SessionStore(context).use { store ->
                store.readableDatabase.rawQuery("SELECT token, profile FROM session", null).use { row ->
                    assertTrue(row.moveToFirst()); assertNotEquals(saved.token, row.getString(0))
                    assertFalse(row.getString(1).contains("Synthetic-test-pass-42"))
                }
            }
            onView(withText("Edit my profile")).perform(scrollTo(), click())
            awaitText("My profile")
            onView(withHint("Full name")).perform(replaceText("Mobile Edited"), closeSoftKeyboard())
            onView(withText("Save profile")).perform(scrollTo(), click())
            awaitText("Welcome, Mobile Edited")
            scenario.recreate()
            awaitText("Welcome, Mobile Edited")
            assertEquals("Mobile Edited", SessionStore(context).use { it.load()!!.user.getString("userName") })
            onView(withText("Request deactivation")).perform(scrollTo(), click())
            onView(withText("Request")).perform(click())
            awaitText("Your deactivation request is awaiting Backoffice review.")
            onView(withText("Sign out")).perform(scrollTo(), click())
            awaitText("You have signed out.")
            assertNull(SessionStore(context).use { it.load() })
        }
    }
    @Test fun operatorSessionRestoresToOperatorHome() {
        val server = InstrumentationRegistry.getArguments().getString("apiUrl")
        assumeTrue("Supply apiUrl for the disposable test API", !server.isNullOrBlank())
        val login = AccountApi(server!!).request("/login", "POST", JSONObject()
            .put("email", "operator@example.test").put("password", "Synthetic-test-pass-42"))
        SessionStore(context).use { it.save(AccountSession(server, login.getString("token"), login.getString("expiresAtUtc"), login.getJSONObject("user"))) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText("Grid Operator home")
            onView(withText("Sign out")).perform(scrollTo(), click())
            awaitText("You have signed out.")
        }
    }
}
