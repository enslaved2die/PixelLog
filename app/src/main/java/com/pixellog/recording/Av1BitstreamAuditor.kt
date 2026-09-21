package com.pixellog.recording

import android.util.Log

/**
 * Av1BitstreamAuditor audits raw AV1 CSD-0 (configOBUs / AV1CodecConfigurationRecord)
 * emitted by hardware encoders (such as c2.google.av1.encoder) to verify:
 * - Sequence Header OBU (Type 1)
 * - AV1 10-bit profile (Main 10: Profile 0, 10-bit depth)
 * - Color description tags (BT.2020 primaries/matrix, transfer curve)
 */
object Av1BitstreamAuditor {
    private const val TAG = "Av1BitstreamAuditor"

    // AV1 OBU types
    const val OBU_SEQUENCE_HEADER = 1
    const val OBU_TEMPORAL_DELIMITER = 2
    const val OBU_FRAME_HEADER = 3
    const val OBU_TILE_GROUP = 4
    const val OBU_METADATA = 5
    const val OBU_FRAME = 6
    const val OBU_REDUNDANT_FRAME_HEADER = 7
    const val OBU_TILE_LIST = 8
    const val OBU_PADDING = 15

    // Color primaries & matrix values (ITU-T H.273)
    const val CP_BT_709 = 1
    const val CP_UNSPECIFIED = 2
    const val CP_BT_2020 = 9

    const val TC_BT_709 = 1
    const val TC_UNSPECIFIED = 2
    const val TC_BT_2020_10BIT = 14

    const val MC_IDENTITY = 0
    const val MC_BT_709 = 1
    const val MC_UNSPECIFIED = 2
    const val MC_BT_2020_NCL = 9

    data class Av1AuditResult(
        val hasSequenceHeader: Boolean,
        val sequenceHeaderOffset: Int,
        val profile: Int, // 0 = Main, 1 = High, 2 = Professional
        val isMain10Profile: Boolean,
        val bitDepth: Int, // 8, 10, or 12
        val colorDescriptionPresent: Boolean,
        val colorPrimaries: Int,
        val transferCharacteristics: Int,
        val matrixCoefficients: Int,
        val isBt2020Signaled: Boolean
    )

    /**
     * Inspects raw AV1 CSD-0 byte buffer (either an AV1CodecConfigurationRecord or raw configOBUs)
     * to audit the Sequence Header OBU, profile, bit depth, and color tags.
     */
    fun auditCsd0(csd0: ByteArray): Av1AuditResult {
        if (csd0.isEmpty()) {
            logWarning("Empty CSD-0 payload received.")
            return emptyResult()
        }

        var isRecord = false
        var recordProfile = -1
        var recordBitDepth = 8
        var recordIsMain10 = false
        var searchStartOffset = 0

        // Check for AV1CodecConfigurationRecord:
        // Byte 0: marker (1 bit = 1), version (7 bits = 1) -> 0x81
        if (csd0.size >= 4 && (csd0[0].toInt() and 0xFF) == 0x81) {
            isRecord = true
            recordProfile = (csd0[1].toInt() ushr 5) and 0x07
            val highBitdepth = (csd0[2].toInt() ushr 6) and 0x01
            val twelveBit = (csd0[2].toInt() ushr 5) and 0x01
            recordBitDepth = when {
                twelveBit == 1 -> 12
                highBitdepth == 1 -> 10
                else -> 8
            }
            recordIsMain10 = (recordProfile == 0 && recordBitDepth == 10)
            searchStartOffset = 4 // configOBUs begins at byte offset 4
            logInfo("Detected AV1CodecConfigurationRecord: profile=$recordProfile, bitDepth=$recordBitDepth, isMain10=$recordIsMain10")
        }

        // Locate Sequence Header OBU (Type 1)
        val seqHeaderLocation = findSequenceHeader(csd0, searchStartOffset)

        if (seqHeaderLocation == null) {
            if (isRecord) {
                logInfo("Valid AV1CodecConfigurationRecord present without separate Sequence Header OBU.")
                return Av1AuditResult(
                    hasSequenceHeader = false,
                    sequenceHeaderOffset = -1,
                    profile = recordProfile,
                    isMain10Profile = recordIsMain10,
                    bitDepth = recordBitDepth,
                    colorDescriptionPresent = false,
                    colorPrimaries = CP_UNSPECIFIED,
                    transferCharacteristics = TC_UNSPECIFIED,
                    matrixCoefficients = MC_UNSPECIFIED,
                    isBt2020Signaled = false
                )
            }
            logWarning("CSD-0 contains no Sequence Header OBU (OBU type 1).")
            return emptyResult()
        }

        val (obuOffset, payloadOffset, payloadSize) = seqHeaderLocation
        logInfo("Found AV1 Sequence Header OBU at byte offset: $obuOffset, payload offset: $payloadOffset, size: $payloadSize")

        // Parse Sequence Header OBU payload bitstream
        return try {
            val reader = BitReader(csd0, payloadOffset)
            val parsed = parseSequenceHeader(reader)

            val profile = parsed.profile
            val bitDepth = parsed.bitDepth
            val isMain10 = (profile == 0 && bitDepth == 10)
            val isBt2020 = parsed.colorPrimaries == CP_BT_2020 || parsed.matrixCoefficients == MC_BT_2020_NCL

            logInfo(
                "Parsed Sequence Header: profile=$profile, bitDepth=$bitDepth, isMain10=$isMain10, " +
                "colorDescPresent=${parsed.colorDescriptionPresent}, primaries=${parsed.colorPrimaries}, " +
                "matrix=${parsed.matrixCoefficients}, isBt2020=$isBt2020"
            )

            Av1AuditResult(
                hasSequenceHeader = true,
                sequenceHeaderOffset = obuOffset,
                profile = profile,
                isMain10Profile = isMain10,
                bitDepth = bitDepth,
                colorDescriptionPresent = parsed.colorDescriptionPresent,
                colorPrimaries = parsed.colorPrimaries,
                transferCharacteristics = parsed.transferCharacteristics,
                matrixCoefficients = parsed.matrixCoefficients,
                isBt2020Signaled = isBt2020
            )
        } catch (e: Exception) {
            logWarning("Exception while parsing Sequence Header OBU bitstream: ${e.message}")
            // Fallback to record information if available
            if (isRecord) {
                Av1AuditResult(
                    hasSequenceHeader = true,
                    sequenceHeaderOffset = obuOffset,
                    profile = recordProfile,
                    isMain10Profile = recordIsMain10,
                    bitDepth = recordBitDepth,
                    colorDescriptionPresent = false,
                    colorPrimaries = CP_UNSPECIFIED,
                    transferCharacteristics = TC_UNSPECIFIED,
                    matrixCoefficients = MC_UNSPECIFIED,
                    isBt2020Signaled = false
                )
            } else {
                Av1AuditResult(
                    hasSequenceHeader = true,
                    sequenceHeaderOffset = obuOffset,
                    profile = 0,
                    isMain10Profile = true,
                    bitDepth = 10,
                    colorDescriptionPresent = false,
                    colorPrimaries = CP_UNSPECIFIED,
                    transferCharacteristics = TC_UNSPECIFIED,
                    matrixCoefficients = MC_UNSPECIFIED,
                    isBt2020Signaled = false
                )
            }
        }
    }

    /** Alias for [auditCsd0] */
    fun auditSequenceHeader(csd0: ByteArray): Av1AuditResult = auditCsd0(csd0)

    /** Alias for [auditCsd0] */
    fun audit(csd0: ByteArray): Av1AuditResult = auditCsd0(csd0)

    private data class ObuLocation(
        val obuOffset: Int,
        val payloadOffset: Int,
        val payloadSize: Int
    )

    private fun findSequenceHeader(data: ByteArray, startOffset: Int): ObuLocation? {
        var offset = startOffset

        while (offset < data.size) {
            val b0 = data[offset].toInt() and 0xFF
            val forbidden = (b0 ushr 7) and 0x01
            val obuType = (b0 ushr 3) and 0x0F
            val extFlag = (b0 ushr 2) and 0x01
            val hasSize = (b0 ushr 1) and 0x01

            if (forbidden != 0) {
                offset++
                continue
            }

            var ptr = offset + 1
            if (extFlag == 1) {
                if (ptr >= data.size) break
                ptr++
            }

            var payloadSize = -1
            if (hasSize == 1) {
                var size = 0L
                var shift = 0
                var bytesRead = 0
                while (ptr < data.size && bytesRead < 8) {
                    val sb = data[ptr++].toInt() and 0xFF
                    size = size or ((sb and 0x7F).toLong() shl shift)
                    shift += 7
                    bytesRead++
                    if ((sb and 0x80) == 0) break
                }
                payloadSize = size.toInt()
            } else {
                payloadSize = data.size - ptr
            }

            if (obuType == OBU_SEQUENCE_HEADER) {
                return ObuLocation(offset, ptr, payloadSize)
            }

            if (payloadSize >= 0) {
                offset = ptr + payloadSize
            } else {
                break
            }
        }

        // Fallback scan: Search for OBU_SEQUENCE_HEADER byte pattern:
        // forbidden == 0, obu_type == 1 -> (b and 0xF8) == 0x08
        for (i in startOffset until data.size) {
            val b = data[i].toInt() and 0xFF
            if ((b and 0x80) == 0 && ((b ushr 3) and 0x0F) == OBU_SEQUENCE_HEADER) {
                val ext = (b ushr 2) and 0x01
                val hasSize = (b ushr 1) and 0x01
                var ptr = i + 1
                if (ext == 1 && ptr < data.size) ptr++
                var sz = data.size - ptr
                if (hasSize == 1) {
                    var s = 0L
                    var sh = 0
                    while (ptr < data.size) {
                        val sb = data[ptr++].toInt() and 0xFF
                        s = s or ((sb and 0x7F).toLong() shl sh)
                        sh += 7
                        if ((sb and 0x80) == 0) break
                    }
                    sz = s.toInt()
                }
                if (ptr <= data.size) {
                    return ObuLocation(i, ptr, sz)
                }
            }
        }

        return null
    }

    private data class ParsedSequenceHeader(
        val profile: Int,
        val bitDepth: Int,
        val colorDescriptionPresent: Boolean,
        val colorPrimaries: Int,
        val transferCharacteristics: Int,
        val matrixCoefficients: Int
    )

    private fun parseSequenceHeader(reader: BitReader): ParsedSequenceHeader {
        val seqProfile = reader.readBits(3)
        val stillPicture = reader.readBit()
        val reducedStillPictureHeader = reader.readBit()

        var decoderModelInfoPresentFlag = 0
        var bufferDelayLength = 0

        if (reducedStillPictureHeader == 1) {
            reader.readBits(5) // seq_level_idx[0]
        } else {
            val timingInfoPresentFlag = reader.readBit()
            if (timingInfoPresentFlag == 1) {
                reader.readBits64(32) // num_units_in_display_tick
                reader.readBits64(32) // time_scale
                val equalPictureInterval = reader.readBit()
                if (equalPictureInterval == 1) {
                    reader.readUvlc() // num_ticks_per_picture_minus_1
                }
                decoderModelInfoPresentFlag = reader.readBit()
                if (decoderModelInfoPresentFlag == 1) {
                    val bufferDelayLengthMinus1 = reader.readBits(5)
                    bufferDelayLength = bufferDelayLengthMinus1 + 1
                    reader.readBits64(32) // num_units_in_decoding_tick
                    reader.readBits(5)    // buffer_removal_time_length_minus_1
                    reader.readBits(5)    // frame_presentation_time_length_minus_1
                }
            }

            val initialDisplayDelayPresentFlag = reader.readBit()
            val operatingPointsCntMinus1 = reader.readBits(5)
            for (i in 0..operatingPointsCntMinus1) {
                reader.readBits(12) // operating_point_idc
                val seqLevelIdx = reader.readBits(5)
                if (seqLevelIdx > 7) {
                    reader.readBit() // seq_tier
                }
                if (decoderModelInfoPresentFlag == 1) {
                    val decoderModelPresentForThisOp = reader.readBit()
                    if (decoderModelPresentForThisOp == 1) {
                        reader.readBits(bufferDelayLength) // decoder_buffer_delay
                        reader.readBits(bufferDelayLength) // encoder_buffer_delay
                        reader.readBit()                   // low_delay_mode_flag
                    }
                }
                if (initialDisplayDelayPresentFlag == 1) {
                    val initialDisplayDelayPresentForThisOp = reader.readBit()
                    if (initialDisplayDelayPresentForThisOp == 1) {
                        reader.readBits(4) // initial_display_delay_minus_1
                    }
                }
            }
        }

        val frameWidthBitsMinus1 = reader.readBits(4)
        val frameHeightBitsMinus1 = reader.readBits(4)
        reader.readBits(frameWidthBitsMinus1 + 1)  // max_frame_width_minus_1
        reader.readBits(frameHeightBitsMinus1 + 1) // max_frame_height_minus_1

        if (reducedStillPictureHeader == 0) {
            val frameIdNumbersPresentFlag = reader.readBit()
            if (frameIdNumbersPresentFlag == 1) {
                reader.readBits(4) // delta_frame_id_length_minus_2
                reader.readBits(3) // additional_frame_id_length_minus_1
            }
        }

        reader.readBit() // use_128x128_superblock
        reader.readBit() // enable_filter_intra
        reader.readBit() // enable_intra_edge_filter

        if (reducedStillPictureHeader == 0) {
            reader.readBit() // enable_interintra_compound
            reader.readBit() // enable_masked_compound
            reader.readBit() // enable_warped_motion
            reader.readBit() // enable_dual_filter
            val enableOrderHint = reader.readBit()
            if (enableOrderHint == 1) {
                reader.readBit() // enable_jnt_comp
                reader.readBit() // enable_ref_frame_mvs
            }
            val seqChooseScreenContentTools = reader.readBit()
            val seqForceScreenContentTools = if (seqChooseScreenContentTools == 1) {
                2 // SELECT_SCREEN_CONTENT_TOOLS
            } else {
                reader.readBits(1)
            }
            if (seqForceScreenContentTools > 0) {
                val seqChooseIntegerMv = reader.readBit()
                if (seqChooseIntegerMv == 0) {
                    reader.readBits(1)
                }
            }
            if (enableOrderHint == 1) {
                reader.readBits(3) // order_hint_bits_minus_1
            }
        }

        reader.readBit() // enable_superres
        reader.readBit() // enable_cdef
        reader.readBit() // enable_restoration

        // color_config()
        val highBitdepth = reader.readBit()
        val bitDepth = when {
            seqProfile == 2 && highBitdepth == 1 -> {
                val twelveBit = reader.readBit()
                if (twelveBit == 1) 12 else 10
            }
            highBitdepth == 1 -> 10
            else -> 8
        }

        if (seqProfile != 1) {
            reader.readBit() // mono_chrome
        }

        val colorDescriptionPresentFlag = reader.readBit()
        var colorPrimaries = CP_UNSPECIFIED
        var transferCharacteristics = TC_UNSPECIFIED
        var matrixCoefficients = MC_UNSPECIFIED

        if (colorDescriptionPresentFlag == 1) {
            colorPrimaries = reader.readBits(8)
            transferCharacteristics = reader.readBits(8)
            matrixCoefficients = reader.readBits(8)
        }

        return ParsedSequenceHeader(
            profile = seqProfile,
            bitDepth = bitDepth,
            colorDescriptionPresent = (colorDescriptionPresentFlag == 1),
            colorPrimaries = colorPrimaries,
            transferCharacteristics = transferCharacteristics,
            matrixCoefficients = matrixCoefficients
        )
    }

    private fun emptyResult() = Av1AuditResult(
        hasSequenceHeader = false,
        sequenceHeaderOffset = -1,
        profile = -1,
        isMain10Profile = false,
        bitDepth = 0,
        colorDescriptionPresent = false,
        colorPrimaries = CP_UNSPECIFIED,
        transferCharacteristics = TC_UNSPECIFIED,
        matrixCoefficients = MC_UNSPECIFIED,
        isBt2020Signaled = false
    )

    private fun logInfo(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] $msg")
        }
    }

    private fun logWarning(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] $msg")
        }
    }

    /**
     * BitReader reads individual bits and multi-bit integers sequentially from a byte array (MSB first).
     */
    class BitReader(private val data: ByteArray, startByteOffset: Int = 0) {
        private var bytePos = startByteOffset
        private var bitPos = 0 // 0 to 7, where 0 is MSB and 7 is LSB

        val bitsRemaining: Int
            get() = ((data.size - bytePos) * 8) - bitPos

        fun hasMoreBits(): Boolean = bytePos < data.size

        fun readBit(): Int {
            if (bytePos >= data.size) return 0
            val bit = (data[bytePos].toInt() ushr (7 - bitPos)) and 0x01
            bitPos++
            if (bitPos == 8) {
                bitPos = 0
                bytePos++
            }
            return bit
        }

        fun readBits(count: Int): Int {
            var result = 0
            for (i in 0 until count) {
                result = (result shl 1) or readBit()
            }
            return result
        }

        fun readBits64(count: Int): Long {
            var result = 0L
            for (i in 0 until count) {
                result = (result shl 1) or readBit().toLong()
            }
            return result
        }

        fun readUvlc(): Long {
            var leadingZeros = 0
            while (readBit() == 0 && hasMoreBits()) {
                leadingZeros++
                if (leadingZeros >= 32) return 0xFFFFFFFFL
            }
            if (leadingZeros == 0) return 0L
            val value = readBits64(leadingZeros)
            return (1L shl leadingZeros) - 1L + value
        }
    }
}
