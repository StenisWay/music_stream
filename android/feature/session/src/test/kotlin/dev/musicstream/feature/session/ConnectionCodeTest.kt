package dev.musicstream.feature.session

import dev.musicstream.feature.session.domain.model.ConnectionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCodeTest {
    // Code in the format musicserver prints (example host).
    private val code = "MS1:eyJ2IjoxLCJob3N0cyI6WyIxOTIuMC4yLjEwIl0sInBvcnQiOjg0NDMsInNjaGVtZSI6Imh0dHBzIiwicGluIjoic2hhMjU2L0E4VkIxQW56ZFdtME5VQVh3S0M1RDcxR0ViWVVlek5kcnRuamd4SEdBNUE9In0"

    @Test
    fun parsesServerCode() {
        val c = ConnectionCode.parse(code)
        assertEquals(listOf("192.0.2.10"), c.hosts)
        assertEquals(8443, c.port)
        assertEquals("https", c.scheme)
        assertEquals("sha256/A8VB1AnzdWm0NUAXwKC5D71GEbYUezNdrtnjgxHGA5A=", c.certPin)
        assertNull(c.publicKey)
        assertEquals("https://192.0.2.10:8443/", c.baseUrl(c.hosts.first()))
    }

    @Test
    fun parsesPublicKeyAndIgnoresRetiredFirebaseFields() {
        // Code from the Firebase era (sid / fb / ek): still pairs, the extra fields are ignored.
        val c = ConnectionCode.parse("MS1:eyJ2IjoxLCJob3N0cyI6WyIxOTIuMC4yLjEwIl0sInBvcnQiOjg0NDMsInNjaGVtZSI6Imh0dHBzIiwicGluIjoic2hhMjU2L0FOM2dyVEJqOGU3eFFYQk1UMlh0N3RlZDdwYWtjaWlMVTNIYTNiWDRBNGc9IiwicGsiOiJNRmt3RXdZSEtvWkl6ajBDQVFZSUtvWkl6ajBEQVFjRFFnQUU4cXJPMFVzV2VkYlBWVjBsa1FxR0s3YkNKYTBaaHNzM1E4cUdrRmlvWnd5ekpDMURmNDBjQkdjYnFGNUpxcGozTmFVdzFxRTR2aFhzMVRpb0RCdm9MUT09Iiwic2lkIjoiYWJjIiwiZmIiOiJodHRwczovL3guZmlyZWJhc2Vpby5jb20iLCJlayI6Im13M0k0bmpCTm1YaVBWb0IyZTZ3blkxRGZTYXRWY2REVVNUOFZ3cUR4U2s9In0")
        assertEquals(listOf("192.0.2.10"), c.hosts)
        assertTrue(c.publicKey!!.startsWith("MFkw"))
    }

    @Test
    fun rejectsPublicKeyThatDoesNotMatchPin() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionCode.parse("MS1:eyJ2IjoxLCJob3N0cyI6WyIxOTIuMC4yLjEwIl0sInBvcnQiOjg0NDMsInNjaGVtZSI6Imh0dHBzIiwicGluIjoic2hhMjU2L0E4VkIxQW56ZFdtME5VQVh3S0M1RDcxR0ViWVVlek5kcnRuamd4SEdBNUE9IiwicGsiOiJNRmt3RXdZSEtvWkl6ajBDQVFZSUtvWkl6ajBEQVFjRFFnQUU4cXJPMFVzV2VkYlBWVjBsa1FxR0s3YkNKYTBaaHNzM1E4cUdrRmlvWnd5ekpDMURmNDBjQkdjYnFGNUpxcGozTmFVdzFxRTR2aFhzMVRpb0RCdm9MUT09Iiwic2lkIjoiYWJjIiwiZmIiOiJodHRwczovL3guZmlyZWJhc2Vpby5jb20iLCJlayI6Im13M0k0bmpCTm1YaVBWb0IyZTZ3blkxRGZTYXRWY2REVVNUOFZ3cUR4U2s9In0")
        }
    }

    @Test
    fun rejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) { ConnectionCode.parse("hello") }
        assertThrows(IllegalArgumentException::class.java) { ConnectionCode.parse("MS1:e30") } // {} -> no hosts
    }

    @Test
    fun manualAddress() {
        val c = ConnectionCode.manual("https://100.64.1.2:9000/", null)
        assertEquals(listOf("100.64.1.2"), c.hosts)
        assertEquals(9000, c.port)
        assertEquals("http", c.scheme) // no pin -> plain http (VPN / LAN only)
        assertEquals(ConnectionCode.DEFAULT_PORT, ConnectionCode.manual("10.0.0.5", null).port)
    }
}
