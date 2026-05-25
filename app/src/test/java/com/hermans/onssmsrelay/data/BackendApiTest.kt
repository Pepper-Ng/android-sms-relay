package com.hermans.onssmsrelay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendApiTest {

    @Test
    fun normalizesBaseUrlWithoutScheme() {
        assertEquals(
            "https://onsrooster.stefhermans.nl",
            BackendApi.normalizeBaseUrl("onsrooster.stefhermans.nl/"),
        )
    }

    @Test
    fun parsesBackendStatusPayload() {
        val payload = JSONObject(
            """
            {
              "public_base_url": "https://onsrooster.stefhermans.nl",
              "login_url": "https://landvanhorne.hasmoves.com",
              "username": "al****ce",
              "fcm_configured": true,
              "sync": {
                "status": "success",
                "current_phase": "ready",
                "auth_ready": true,
                "last_message": "De backend is klaar.",
                "last_error": "",
                "last_success_at": "2026-05-24T12:00:00Z"
              }
            }
            """.trimIndent(),
        )

        val status = BackendApi.parseStatus(payload)

        assertEquals("success", status.sync.statusCode)
        assertEquals("ready", status.sync.currentPhase)
        assertTrue(status.fcmConfigured)
    }
}