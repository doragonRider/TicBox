package dev.ticbox.companion.engine.privacy

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.cloud.*
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class CloudSettingsTest {
    @Test fun `fresh settings are local only and only the frontend selection survives restart`() = runTest {
        var stored: ByteArray? = null
        val settings = CloudSettingsController(persist = { stored = CloudPrivacyCodec.encode(it) })
        assertTrue(settings.state.value.enabledCapabilities.isEmpty())
        settings.setEnabled(CloudCapability.MOBVOI_CLOUD, true)
        assertEquals(setOf(CloudCapability.MOBVOI_CLOUD), CloudPrivacyCodec.decode(assertNotNull(stored)).enabledCapabilities)
        settings.setEnabled(CloudCapability.MOBVOI_CLOUD, false)
        assertTrue(CloudPrivacyCodec.decode(assertNotNull(stored)).enabledCapabilities.isEmpty())
        assertTrue(CloudPrivacyCodec.decode("ticbox-cloud-privacy/v1\nFUTURE_CLOUD=true\n".toByteArray()).enabledCapabilities.isEmpty())
        assertFails { CloudPrivacyCodec.decode("unsupported/v2".toByteArray()) }
    }

    @Test fun `optional HTTP service is not created until enabled and stop releases it`() = runTest {
        val settings = CloudSettingsController()
        var created = 0
        var requests = 0
        var stopped = 0
        val http = ConfiguredCloudHttpTransport({ settings.state.value }) {
            created++
            object : CloudHttpTransport, AutoCloseable {
                override suspend fun execute(request: CloudHttpRequest): CloudHttpResponse {
                    requests++; return CloudHttpResponse(200, byteArrayOf())
                }
                override fun close() { stopped++ }
            }
        }
        val request = CloudHttpRequest("GET", "https://fixture.invalid/")
        assertEquals(MobvoiCloudIssueKind.CONSENT_REQUIRED, assertFailsWith<MobvoiCloudException> { http.execute(request) }.issue.kind)
        assertEquals(0, created)
        settings.setEnabled(CloudCapability.MOBVOI_CLOUD, true)
        http.execute(request)
        assertEquals(1, requests)
        settings.setEnabled(CloudCapability.MOBVOI_CLOUD, false)
        http.stop()
        assertEquals(1, stopped)
        assertFailsWith<MobvoiCloudException> { http.execute(request) }
        settings.setEnabled(CloudCapability.MOBVOI_CLOUD, true)
        http.execute(request)
        assertEquals(2, created)
        http.close()
    }

    @Test fun `setup uses frontend settings without changing the protocol engine`() = runTest {
        var received: SetupOptions? = null
        val actual = object : SetupClient {
            override val state = MutableStateFlow(SetupSnapshot())
            override suspend fun probe(): SetupProbe = error("unused")
            override suspend fun start(options: SetupOptions) { received = options }
            override fun resume(actionId: Long, response: SetupUserResponse) = false
            override fun cancel() {}
            override fun close() {}
        }
        var settings = CloudPrivacySettings()
        val setup = ConfiguredSetupClient(actual) { settings }
        val accepted = SetupOptions(accountChoice = SetupAccountChoice.TRANSFER,
            checkinChoice = SetupCheckinChoice.ATTEMPT, consents = listOf(
            SetupConsent(SetupOptinType.CLOUDSYNC, SetupOptinChoice.ACCEPTED, 1),
            SetupConsent(SetupOptinType.NOTIFICATIONS, SetupOptinChoice.ACCEPTED, 1)))
        setup.start(accepted)
        assertEquals(SetupAccountChoice.SKIP, received?.accountChoice)
        assertEquals(SetupCheckinChoice.ATTEMPT, received?.checkinChoice)
        assertEquals(listOf(SetupOptinChoice.DECLINED, SetupOptinChoice.ACCEPTED), received?.consents?.map { it.choice })
        settings = CloudPrivacySettings(setOf(CloudCapability.GOOGLE_ACCOUNT_TRANSFER, CloudCapability.GOOGLE_CLOUD_SYNC))
        setup.start(accepted)
        assertEquals(accepted, received)
    }

    @Test fun `Google service stays idle until frontend opts in`() = runTest {
        var transfers = 0
        var cloudCalls = 0
        var decisions: List<SetupConsent> = emptyList()
        val actual = object : GoogleServices {
            override val state = MutableStateFlow(GoogleServicesState())
            override val actions = MutableStateFlow<List<GoogleUserAction>>(emptyList())
            override suspend fun transferAccount(peer: PeerAddress, sessionId: Long, request: GoogleAccountTransferRequest): GoogleServiceResult {
                transfers++; return GoogleServiceResult(GoogleServiceStatus.COMPLETE, "account_transfer")
            }
            override suspend fun applyConsents(peer: PeerAddress, sessionId: Long, consents: List<SetupConsent>): List<GoogleServiceResult> {
                decisions = consents; return emptyList()
            }
            override suspend fun setCloudSyncSetting(peer: PeerAddress, sessionId: Long, enabled: Boolean): GoogleServiceResult {
                cloudCalls++; return GoogleServiceResult(GoogleServiceStatus.COMPLETE, "cloud_sync_setting")
            }
            override fun abortAccountTransfer() {}
            override fun close() {}
        }
        var settings = CloudPrivacySettings()
        val google = ConfiguredGoogleServices(actual) { settings }
        val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
        assertEquals(GoogleServiceStatus.SKIPPED, google.transferAccount(peer, 1).status)
        assertEquals(GoogleServiceStatus.SKIPPED, google.setCloudSyncSetting(peer, 1, true).status)
        assertEquals(0, transfers); assertEquals(0, cloudCalls)
        google.applyConsents(peer, 1, listOf(
            SetupConsent(SetupOptinType.CLOUDSYNC, SetupOptinChoice.ACCEPTED, 1),
            SetupConsent(SetupOptinType.LOGGING, SetupOptinChoice.ACCEPTED, 1)))
        assertEquals(listOf(SetupOptinType.LOGGING), decisions.map { it.type })
        assertEquals(SetupOptinChoice.DECLINED, decisions.single().choice)
        settings = CloudPrivacySettings(setOf(CloudCapability.GOOGLE_ACCOUNT_TRANSFER, CloudCapability.GOOGLE_CLOUD_SYNC))
        google.transferAccount(peer, 1); google.setCloudSyncSetting(peer, 1, true)
        assertEquals(1, transfers); assertEquals(1, cloudCalls)
    }
}
