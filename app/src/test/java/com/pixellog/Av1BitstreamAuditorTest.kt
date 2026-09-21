package com.pixellog

import com.pixellog.recording.Av1BitstreamAuditor
import org.junit.Assert.*
import org.junit.Test

class Av1BitstreamAuditorTest {

    @Test
    fun testAuditCsd0_Empty() {
        val result = Av1BitstreamAuditor.auditCsd0(ByteArray(0))
        assertFalse(result.hasSequenceHeader)
        assertEquals(-1, result.sequenceHeaderOffset)
    }

    @Test
    fun testAuditCsd0_WithAv1CodecConfigurationRecord() {
        // Construct synthetic AV1CodecConfigurationRecord (ISOBMFF 'av1C'):
        // Byte 0: 0x81 (marker 1 bit = 1, version 7 bits = 1)
        // Byte 1: seq_profile (3 bits = 0: Main) | seq_level_idx_0 (5 bits = 0) -> 0x00
        // Byte 2: seq_tier_0 (1 bit = 0) | high_bitdepth (1 bit = 1) | twelve_bit (1 bit = 0) | monochrome (1 bit = 0) | ... -> 0x40
        // Byte 3: initial_presentation_delay_present (1 bit = 0) | initial_presentation_delay_minus_one (4 bits = 0) -> 0x00
        val record = byteArrayOf(
            0x81.toByte(),
            0x00.toByte(),
            0x40.toByte(), // high_bitdepth = 1 (10-bit)
            0x00.toByte()
        )

        val result = Av1BitstreamAuditor.auditCsd0(record)
        assertEquals(0, result.profile)
        assertEquals(10, result.bitDepth)
        assertTrue(result.isMain10Profile)
    }

    @Test
    fun testAuditCsd0_WithSequenceHeaderObu() {
        // Construct raw OBU Sequence Header:
        // Byte 0: obu_forbidden_bit (0), obu_type (1 = Sequence Header: 1 << 3 = 8), obu_extension_flag (0), obu_has_size_field (1) -> 0x0A or 0x08
        // Byte 1: leb128 size (e.g. 4 bytes) -> 0x04
        // Payload bytes...
        val obu = byteArrayOf(
            0x0A.toByte(), // obu_header: type=1 (Sequence Header), obu_has_size_field=1
            0x04.toByte(), // size = 4
            0x00.toByte(), // profile = 0 (3 bits)
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte()
        )

        val result = Av1BitstreamAuditor.auditCsd0(obu)
        assertTrue(result.hasSequenceHeader)
        assertEquals(0, result.sequenceHeaderOffset)
        assertEquals(0, result.profile)
    }
}
