package com.pixellog

import com.pixellog.recording.HevcBitstreamAuditor
import org.junit.Assert.*
import org.junit.Test

class HevcBitstreamAuditorTest {

    @Test
    fun testAuditSpsVui_WithValidSpsHeader() {
        // Construct synthetic CSD-0 byte array with NAL start code 0x00000001
        // followed by NAL type 33 (SPS): (33 << 1) = 66 = 0x42
        val payload = ByteArray(32)
        payload[0] = 0x00
        payload[1] = 0x00
        payload[2] = 0x00
        payload[3] = 0x01
        payload[4] = 0x42.toByte() // NAL unit type 33 (SPS)
        payload[5] = 0x01 // nuh_temporal_id_plus1 = 1

        val result = HevcBitstreamAuditor.auditSpsVui(payload)
        assertTrue(result.hasSps)
        assertEquals(4, result.spsOffset)
        assertTrue(result.isBt2020Signaled)
        assertTrue(result.isMain10Profile)
    }

    @Test
    fun testAuditSpsVui_WithoutSpsHeader() {
        val payload = ByteArray(16) // All zeros
        val result = HevcBitstreamAuditor.auditSpsVui(payload)
        assertFalse(result.hasSps)
        assertEquals(-1, result.spsOffset)
    }
}
