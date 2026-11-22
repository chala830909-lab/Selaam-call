package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class CallType(val wireValue: String) {
    AUDIO("audio"),
    VIDEO("video");

    companion object {
        fun fromWire(raw: String?): CallType =
            entries.firstOrNull { it.wireValue == raw } ?: AUDIO
    }
}

enum class CallStatus(val wireValue: String) {
    RINGING("ringing"),
    ACCEPTED("accepted"),
    DECLINED("declined"),
    ENDED("ended"),
    MISSED("missed");

    val isTerminal: Boolean
        get() = this == DECLINED || this == ENDED || this == MISSED

    companion object {
        fun fromWire(raw: String?): CallStatus =
            entries.firstOrNull { it.wireValue == raw } ?: ENDED
    }
}

enum class CallHistoryFilter(val label: String) {
    ALL("All"),
    INCOMING("Incoming"),
    OUTGOING("Outgoing"),
    MISSED("Missed")
}

@Keep
@IgnoreExtraProperties
data class CallSession(
    @DocumentId val callId: String = "",
    val callerId: String = "",
    val callerUsername: String = "",
    val callerDisplayName: String = "",
    val callerPhone: String = "",
    val calleeId: String = "",
    val calleeUsername: String = "",
    val calleeDisplayName: String = "",
    val calleePhone: String = "",
    val participantUids: List<String> = emptyList(),
    val callType: String = CallType.AUDIO.wireValue,
    val status: String = CallStatus.RINGING.wireValue,
    val callerVideoEnabled: Boolean = false,
    val calleeVideoEnabled: Boolean = false,
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
    @ServerTimestamp val endedAt: Timestamp? = null
) {
    fun parsedType(): CallType = CallType.fromWire(callType)
    fun parsedStatus(): CallStatus = CallStatus.fromWire(status)

    fun isOutgoingFor(uid: String): Boolean = callerId == uid

    fun peerId(currentUid: String): String =
        if (callerId == currentUid) calleeId else callerId

    fun peerDisplayName(currentUid: String): String =
        if (callerId == currentUid) calleeDisplayName else callerDisplayName

    fun peerUsername(currentUid: String): String =
        if (callerId == currentUid) calleeUsername else callerUsername

    fun peerPhone(currentUid: String): String =
        if (callerId == currentUid) calleePhone else callerPhone

    fun isRemoteVideoEnabled(currentUid: String): Boolean =
        if (callerId == currentUid) calleeVideoEnabled else callerVideoEnabled
}

@Keep
@IgnoreExtraProperties
data class IceCandidateRecord(
    @DocumentId val candidateId: String = "",
    val senderId: String = "",
    val participantUids: List<String> = emptyList(),
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
