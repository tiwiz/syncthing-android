package com.nutomic.syncthingandroid.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTest {

    private fun device(id: String = "", name: String = "", addresses: List<String>? = null) =
        Device().apply {
            deviceID = id
            this.name = name
            this.addresses = addresses
        }

    // Accepted and rejected forms from Syncthing's lib/protocol/deviceid_test.go.
    @Test
    fun `accepts the device id forms syncthing accepts`() {
        listOf(
                "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2",
                "P56IOI7 MZJNU2Y IQGDREY DM2MGTI MGL3BXN PQ6W5BM TBBZ4TJ XZWICQ2",
                "P56IOI7MZJNU2YIQGDREYDM2MGTIMGL3BXNPQ6W5BMTBBZ4TJXZWICQ2",
                "p56ioi7mzjnu2yiqgdreydm2mgtimgl3bxnpq6w5bmtbbz4tjxzwicq2",
                "P56IOI7MZJNU2IQGDREYDM2MGTMGL3BXNPQ6W5BTBBZ4TJXZWICQ",
                "P56IOI7-MZJNU2-IQGDREY-DM2MGT-MGL3BXN-PQ6W5B-TBBZ4TJ-XZWICQ",
                "P56IOI7 MZJNU2I QGDREYD M2MGTMGL 3BXNPQ6W 5BTB BZ4T JXZWICQ",
            )
            .forEach { assertTrue(it, device(id = it).checkDeviceID()) }
    }

    @Test
    fun `typo-tolerant digits are mapped like syncthing does`() {
        // 0 -> O, 1 -> I, 8 -> B
        assertTrue(device("P56I0I7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2").checkDeviceID())
        assertTrue(device("P56IO17-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-T88Z4TJ-XZWICQ2").checkDeviceID())
    }

    @Test
    fun `rejects malformed device ids`() {
        listOf(
                "",
                "P56IOI7MZJNU2IQGDREYDM2MGTMGL3BXNPQ6W5BTBBZ4TJXZWICQCCCC",
                "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ3",
                "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWIC",
                "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWIC!2",
            )
            .forEach { assertFalse(it, device(id = it).checkDeviceID()) }
    }

    @Test
    fun `display name falls back to the first seven characters of the id`() {
        assertEquals("laptop", device(id = "P56IOI7-MZJNU2Y", name = "laptop").getDisplayName())
        assertEquals("P56IOI7", device(id = "P56IOI7-MZJNU2Y").getDisplayName())
        assertEquals("", device().getDisplayName())
    }

    @Test
    fun `address validation`() {
        val valid =
            listOf(
                "dynamic",
                "tcp://127.0.0.1:4000",
                "tcp4://127.0.0.1",
                "tcp6://[2001:db8::23:42]:12345",
                "tcp://myserver",
                "quic://127.0.0.1:24000",
                "quic6://127.0.0.1",
                "relay://stlocal:22067/?id=X&statusAddr=:22070",
            )
        val invalid =
            listOf(
                "tcp://myserver:",
                "tcp://:4000",
                "tcp://myserver:0",
                "tcp://myserver:65536",
                "tcp://myserver:port",
                "tcp8://127.0.0.1",
                "udp4://127.0.0.1",
                "tcp://",
                "tcp://tcp://host",
                "myserver:22000",
                "relay://:22067",
            )
        valid.forEach { assertTrue(it, device(addresses = listOf(it)).checkDeviceAddresses()) }
        invalid.forEach { assertFalse(it, device(addresses = listOf(it)).checkDeviceAddresses()) }
        assertFalse("null list", device(addresses = null).checkDeviceAddresses())
        assertTrue("empty list", device(addresses = emptyList()).checkDeviceAddresses())
        assertFalse(
            "one bad address spoils the list",
            device(addresses = listOf("dynamic", "tcp://myserver:")).checkDeviceAddresses(),
        )
    }
}
