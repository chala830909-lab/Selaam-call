package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.model.CallSession
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.data.model.DirectoryEntry
import com.example.data.model.IceCandidateRecord
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CallSignalingRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before signaling calls.")
    }

    fun observeMyCalls(userId: String): Flow<List<CallSession>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user call observation." }
        val path = "calls"
        emitAll(
            db.collection("calls")
                .whereArrayContains("participantUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CallSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeCall(callId: String): Flow<CallSession?> = flow {
        val uid = requireUserId()
        val path = "calls/$callId"
        emitAll(
            db.collection("calls").document(callId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            CallSession::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createCallOffer(
        caller: UserProfile,
        callee: DirectoryEntry,
        callType: CallType,
        offerSdp: String
    ): Result<CallSession> {
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val path = "calls/$callId"
        return try {
            val uid = requireUserId()
            check(uid == caller.uid) { "Caller UID mismatch." }
            require(caller.uid != callee.uid) { "Cannot place a call to yourself." }
            val videoOn = callType == CallType.VIDEO
            val participantUids = listOf(caller.uid, callee.uid)
            val payload = mapOf(
                "callerId" to caller.uid,
                "callerUsername" to caller.username,
                "callerDisplayName" to caller.displayName,
                "callerPhone" to caller.phoneNumber,
                "calleeId" to callee.uid,
                "calleeUsername" to callee.username,
                "calleeDisplayName" to callee.displayName,
                "calleePhone" to callee.phoneNumber,
                "participantUids" to participantUids,
                "callType" to callType.wireValue,
                "status" to CallStatus.RINGING.wireValue,
                "callerVideoEnabled" to videoOn,
                "calleeVideoEnabled" to videoOn,
                "offerSdp" to offerSdp.take(32768),
                "durationSeconds" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("calls").document(callId).set(payload).await()
            Result.success(
                CallSession(
                    callId = callId,
                    callerId = caller.uid,
                    callerUsername = caller.username,
                    callerDisplayName = caller.displayName,
                    callerPhone = caller.phoneNumber,
                    calleeId = callee.uid,
                    calleeUsername = callee.username,
                    calleeDisplayName = callee.displayName,
                    calleePhone = callee.phoneNumber,
                    participantUids = participantUids,
                    callType = callType.wireValue,
                    status = CallStatus.RINGING.wireValue,
                    callerVideoEnabled = videoOn,
                    calleeVideoEnabled = videoOn,
                    offerSdp = offerSdp,
                    durationSeconds = 0L
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun acceptCall(
        callId: String,
        answerSdp: String,
        calleeVideoEnabled: Boolean
    ): Result<Unit> {
        val path = "calls/$callId"
        return try {
            requireUserId()
            db.collection("calls").document(callId).update(
                mapOf(
                    "status" to CallStatus.ACCEPTED.wireValue,
                    "answerSdp" to answerSdp.take(32768),
                    "calleeVideoEnabled" to calleeVideoEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun declineCall(callId: String): Result<Unit> {
        val path = "calls/$callId"
        return try {
            requireUserId()
            db.collection("calls").document(callId).update(
                mapOf(
                    "status" to CallStatus.DECLINED.wireValue,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun endCall(
        callId: String,
        durationSeconds: Long,
        markAsMissed: Boolean = false
    ): Result<Unit> {
        val path = "calls/$callId"
        return try {
            requireUserId()
            val safeDuration = durationSeconds.coerceIn(0L, 86400L)
            val finalStatus = if (markAsMissed) CallStatus.MISSED else CallStatus.ENDED
            db.collection("calls").document(callId).update(
                mapOf(
                    "status" to finalStatus.wireValue,
                    "durationSeconds" to safeDuration,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateVideoEnabled(
        callId: String,
        isCaller: Boolean,
        enabled: Boolean
    ): Result<Unit> {
        val path = "calls/$callId"
        return try {
            requireUserId()
            val field = if (isCaller) "callerVideoEnabled" else "calleeVideoEnabled"
            val updates = mutableMapOf<String, Any>(
                field to enabled,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (enabled) {
                updates["callType"] = CallType.VIDEO.wireValue
            }
            db.collection("calls").document(callId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun sendIceCandidate(
        callId: String,
        isCaller: Boolean,
        participantUids: List<String>,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String
    ): Result<Unit> {
        val subcol = if (isCaller) "callerCandidates" else "calleeCandidates"
        val candId = "ice_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val path = "calls/$callId/$subcol/$candId"
        return try {
            val uid = requireUserId()
            val payload = mapOf(
                "senderId" to uid,
                "participantUids" to participantUids,
                "sdpMid" to sdpMid.take(64),
                "sdpMLineIndex" to sdpMLineIndex.coerceIn(0, 32),
                "candidate" to candidate.take(2048),
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("calls").document(callId)
                .collection(subcol).document(candId)
                .set(payload)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    fun observeRemoteIceCandidates(
        callId: String,
        listenForCallerCandidates: Boolean,
        currentUserId: String
    ): Flow<List<IceCandidateRecord>> = flow {
        val uid = requireUserId()
        check(uid == currentUserId) { "Refusing cross-user ICE observation." }
        val subcol = if (listenForCallerCandidates) "callerCandidates" else "calleeCandidates"
        val path = "calls/$callId/$subcol"
        emitAll(
            db.collection("calls").document(callId)
                .collection(subcol)
                .whereArrayContains("participantUids", currentUserId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        IceCandidateRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }
}
