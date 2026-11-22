package com.example

import com.example.data.model.CallSession
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.data.model.CountryCodes
import com.example.data.model.DirectoryEntry
import com.example.ui.screens.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelamCallModelTest {

    @Test
    fun countryCodeDefaultsToEthiopia251() {
        assertEquals("+251", CountryCodes.defaultCountry.dialCode)
        assertEquals("+251983156394", CountryCodes.formatE164("+251", "0983156394"))
        assertEquals("251983156394", CountryCodes.toPhoneClaimKey("+251983156394"))
    }

    @Test
    fun usernameSanitizationStripsAtAndLowercases() {
        assertEquals("chala", CountryCodes.sanitizeUsername(" @Chala "))
        assertEquals("abebe_1", CountryCodes.sanitizeUsername("Abebe_1"))
    }

    @Test
    fun directoryEntryInitials() {
        val twoWords = DirectoryEntry(displayName = "Chala Mohammed", username = "chala")
        assertEquals("CM", twoWords.initials())
        val singleWord = DirectoryEntry(displayName = "Abebe", username = "abebe")
        assertEquals("AB", singleWord.initials())
    }

    @Test
    fun callSessionPeerResolutionAndFormatting() {
        val session = CallSession(
            callId = "call_1",
            callerId = "u1",
            callerUsername = "chala",
            callerDisplayName = "Chala M",
            callerPhone = "+251983156394",
            calleeId = "u2",
            calleeUsername = "abebe",
            calleeDisplayName = "Abebe B",
            calleePhone = "+251911223344",
            participantUids = listOf("u1", "u2"),
            callType = "video",
            status = "accepted",
            callerVideoEnabled = true,
            calleeVideoEnabled = false
        )

        assertTrue(session.isOutgoingFor("u1"))
        assertFalse(session.isOutgoingFor("u2"))
        assertEquals("Abebe B", session.peerDisplayName("u1"))
        assertEquals("Chala M", session.peerDisplayName("u2"))
        assertEquals(CallType.VIDEO, session.parsedType())
        assertEquals(CallStatus.ACCEPTED, session.parsedStatus())
        assertFalse(session.isRemoteVideoEnabled("u1"))
        assertTrue(session.isRemoteVideoEnabled("u2"))
        assertEquals("02:05", formatDuration(125L))
    }
}
