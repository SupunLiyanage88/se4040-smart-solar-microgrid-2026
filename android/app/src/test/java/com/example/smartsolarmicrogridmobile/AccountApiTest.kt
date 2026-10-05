package com.example.smartsolarmicrogridmobile

import org.junit.Test

class AccountApiTest {
    @Test
    fun configuredBackendIsAccepted() {
        AccountApi(BuildConfig.API_BASE_URL)
    }

    @Test(expected = IllegalArgumentException::class)
    fun addressWithCredentialsIsRejected() {
        AccountApi("http://user:password@13.233.15.160:8080")
    }
}
