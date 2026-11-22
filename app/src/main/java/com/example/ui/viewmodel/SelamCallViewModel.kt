package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.CallSignalingRepository
import com.example.data.firebase.UserDirectoryRepository
import com.example.data.model.CallHistoryFilter
import com.example.data.model.CallSession
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.data.model.DirectoryEntry
import com.example.data.model.UserProfile
import com.example.webrtc.RingtonePlayer
import com.example.webrtc.WebRtcConnectionState
import com.example.webrtc.WebRtcMediaManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class SelamCallViewModel(
    val currentUserId: String,
    private val userEmail: String,
    private val googleDisplayName: String,
    private val userRepo: UserDirectoryRepository,
    private val callRepo: CallSignalingRepository,
    private val mediaManager: WebRtcMediaManager,
    private val ringtonePlayer: RingtonePlayer
) : ViewModel() {

    val eglBaseContext: EglBase.Context
        get() = mediaManager.eglBase.eglBaseContext

    val localVideoTrack: StateFlow<VideoTrack?> = mediaManager.localVideoTrack
    val remoteVideoTrack: StateFlow<VideoTrack?> = mediaManager.remoteVideoTrack
    val isMuted: StateFlow<Boolean> = mediaManager.isMuted
    val isCameraEnabled: StateFlow<Boolean> = mediaManager.isCameraEnabled
    val isSpeakerOn: StateFlow<Boolean> = mediaManager.isSpeakerOn
    val isFrontCamera: StateFlow<Boolean> = mediaManager.isFrontCamera
    val connectionState: StateFlow<WebRtcConnectionState> = mediaManager.connectionState

    val myProfileState: StateFlow<UiState<UserProfile?>> = userRepo.observeMyProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Failed to observe user profile", root)
            emit(UiState.Error(root.message ?: "Failed to load profile"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    val directoryState: StateFlow<UiState<List<DirectoryEntry>>> = userRepo.observeDirectory()
        .map<List<DirectoryEntry>, UiState<List<DirectoryEntry>>> { entries ->
            val filtered = entries
                .filter { it.uid != currentUserId }
                .sortedWith(
                    compareByDescending<DirectoryEntry> { it.onlineStatus }
                        .thenBy { it.displayName.lowercase() }
                )
            UiState.Success(filtered)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Failed to observe directory", root)
            emit(UiState.Error(root.message ?: "Failed to load contacts directory"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    private val allCallsState: StateFlow<UiState<List<CallSession>>> = callRepo.observeMyCalls(currentUserId)
        .map<List<CallSession>, UiState<List<CallSession>>> { calls ->
            val sorted = calls.sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            UiState.Success(sorted)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Failed to observe calls", root)
            emit(UiState.Error(root.message ?: "Failed to load calls"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading
        )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _onlineOnlyFilter = MutableStateFlow(false)
    val onlineOnlyFilter: StateFlow<Boolean> = _onlineOnlyFilter.asStateFlow()

    val filteredDirectoryState: StateFlow<UiState<List<DirectoryEntry>>> = combine(
        directoryState,
        _searchQuery,
        _onlineOnlyFilter
    ) { state, query, onlineOnly ->
        when (state) {
            is UiState.Loading -> UiState.Loading
            is UiState.Error -> state
            is UiState.Success -> {
                val q = query.trim().lowercase()
                val cleanQ = q.removePrefix("@")
                val digitsQ = q.filter { it.isDigit() }
                val list = state.data.filter { entry ->
                    val matchesOnline = !onlineOnly || entry.onlineStatus
                    val matchesQuery = if (q.isBlank()) {
                        true
                    } else {
                        entry.username.lowercase().contains(cleanQ) ||
                            entry.displayName.lowercase().contains(q) ||
                            entry.phoneNumber.lowercase().contains(q) ||
                            (digitsQ.isNotEmpty() && entry.phoneNumber.filter { it.isDigit() }.contains(digitsQ))
                    }
                    matchesOnline && matchesQuery
                }
                UiState.Success(list)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading
    )

    private val _historyFilter = MutableStateFlow(CallHistoryFilter.ALL)
    val historyFilter: StateFlow<CallHistoryFilter> = _historyFilter.asStateFlow()

    val filteredCallHistoryState: StateFlow<UiState<List<CallSession>>> = combine(
        allCallsState,
        _historyFilter
    ) { state, filter ->
        when (state) {
            is UiState.Loading -> UiState.Loading
            is UiState.Error -> state
            is UiState.Success -> {
                val finished = state.data.filter { call ->
                    call.parsedStatus().isTerminal || call.parsedStatus() == CallStatus.ACCEPTED
                }
                val filtered = when (filter) {
                    CallHistoryFilter.ALL -> finished
                    CallHistoryFilter.INCOMING -> finished.filter {
                        !it.isOutgoingFor(currentUserId) &&
                            (it.parsedStatus() == CallStatus.ENDED || it.parsedStatus() == CallStatus.ACCEPTED)
                    }
                    CallHistoryFilter.OUTGOING -> finished.filter {
                        it.isOutgoingFor(currentUserId)
                    }
                    CallHistoryFilter.MISSED -> finished.filter {
                        !it.isOutgoingFor(currentUserId) &&
                            (it.parsedStatus() == CallStatus.MISSED || it.parsedStatus() == CallStatus.DECLINED)
                    }
                }
                UiState.Success(filtered)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading
    )

    private val _incomingCall = MutableStateFlow<CallSession?>(null)
    val incomingCall: StateFlow<CallSession?> = _incomingCall.asStateFlow()

    private val _activeCallSession = MutableStateFlow<CallSession?>(null)
    val activeCallSession: StateFlow<CallSession?> = _activeCallSession.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _isSubmittingProfile = MutableStateFlow(false)
    val isSubmittingProfile: StateFlow<Boolean> = _isSubmittingProfile.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _isEditingProfile = MutableStateFlow(false)
    val isEditingProfile: StateFlow<Boolean> = _isEditingProfile.asStateFlow()

    private val handledIncomingCallIds = mutableSetOf<String>()
    private val processedIceCandidateIds = mutableSetOf<String>()

    private var activeCallObserverJob: Job? = null
    private var remoteIceObserverJob: Job? = null
    private var callTimerJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var answerApplied = false

    init {
        observeIncomingCalls()
    }

    private fun observeIncomingCalls() {
        viewModelScope.launch {
            allCallsState.collect { state ->
                if (state !is UiState.Success) return@collect
                val activeId = _activeCallSession.value?.callId
                val currentIncoming = _incomingCall.value

                // Check if current incoming call was cancelled or ended by caller
                if (currentIncoming != null) {
                    val latest = state.data.firstOrNull { it.callId == currentIncoming.callId }
                    if (latest == null || latest.parsedStatus() != CallStatus.RINGING) {
                        ringtonePlayer.stop()
                        _incomingCall.value = null
                    }
                }

                // Look for a fresh incoming ringing call
                if (activeId == null && _incomingCall.value == null) {
                    val ringing = state.data.firstOrNull { call ->
                        call.calleeId == currentUserId &&
                            call.parsedStatus() == CallStatus.RINGING &&
                            !handledIncomingCallIds.contains(call.callId)
                    }
                    if (ringing != null) {
                        _incomingCall.value = ringing
                        ringtonePlayer.startIncomingRingtone()
                    }
                }
            }
        }
    }

    fun suggestedDisplayName(): String = googleDisplayName

    fun suggestedEmail(): String = userEmail

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleOnlineOnlyFilter() {
        _onlineOnlyFilter.value = !_onlineOnlyFilter.value
    }

    fun selectHistoryFilter(filter: CallHistoryFilter) {
        _historyFilter.value = filter
    }

    fun openEditProfile() {
        _isEditingProfile.value = true
    }

    fun closeEditProfile() {
        _isEditingProfile.value = false
    }

    fun clearBannerMessage() {
        _bannerMessage.value = null
    }

    fun showBannerMessage(message: String) {
        _bannerMessage.value = message
    }

    fun registerOrUpdateIdentity(
        displayName: String,
        rawUsername: String,
        countryCode: String,
        rawLocalPhone: String,
        discoverable: Boolean
    ) {
        val currentProfile = (myProfileState.value as? UiState.Success)?.data
        viewModelScope.launch {
            _isSubmittingProfile.value = true
            val result = userRepo.registerDualIdentity(
                existingProfile = currentProfile,
                displayName = displayName,
                rawUsername = rawUsername,
                countryCode = countryCode,
                rawLocalPhone = rawLocalPhone,
                discoverable = discoverable
            )
            _isSubmittingProfile.value = false
            result.fold(
                onSuccess = {
                    _isEditingProfile.value = false
                    _bannerMessage.value = "Identity saved as @${it.username}"
                },
                onFailure = { error ->
                    _bannerMessage.value = error.message ?: "Failed to save profile"
                }
            )
        }
    }

    fun setOnlineStatus(online: Boolean) {
        viewModelScope.launch {
            userRepo.updatePresence(currentUserId, online)
        }
    }

    fun startOutgoingCall(
        callee: DirectoryEntry,
        callType: CallType,
        hasCameraPermission: Boolean
    ) {
        val myProfile = (myProfileState.value as? UiState.Success)?.data
        if (myProfile == null) {
            _bannerMessage.value = "Complete your profile identity before calling."
            return
        }
        if (_activeCallSession.value != null) {
            _bannerMessage.value = "Another call is already active."
            return
        }

        viewModelScope.launch {
            try {
                resetCallInternalState()
                val enableVideo = callType == CallType.VIDEO && hasCameraPermission
                mediaManager.startSession(
                    enableVideo = enableVideo,
                    hasCameraPermission = hasCameraPermission,
                    onLocalIceCandidate = { cand ->
                        val call = _activeCallSession.value ?: return@startSession
                        viewModelScope.launch {
                            callRepo.sendIceCandidate(
                                callId = call.callId,
                                isCaller = true,
                                participantUids = call.participantUids,
                                sdpMid = cand.sdpMid ?: "0",
                                sdpMLineIndex = cand.sdpMLineIndex,
                                candidate = cand.sdp
                            )
                        }
                    }
                )

                val offerSdp = mediaManager.createOfferSdp()
                val createResult = callRepo.createCallOffer(
                    caller = myProfile,
                    callee = callee,
                    callType = callType,
                    offerSdp = offerSdp
                )
                createResult.fold(
                    onSuccess = { session ->
                        _activeCallSession.value = session
                        ringtonePlayer.startOutgoingRingback()
                        attachActiveCallListeners(session.callId, isCaller = true)
                        startRingTimeout(session.callId)
                    },
                    onFailure = { err ->
                        mediaManager.stopSession()
                        _bannerMessage.value = err.message ?: "Unable to start call"
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error starting outgoing call", e)
                mediaManager.stopSession()
                _bannerMessage.value = e.localizedMessage ?: "Failed to initialize call"
            }
        }
    }

    fun redialFromHistory(
        session: CallSession,
        callType: CallType,
        hasCameraPermission: Boolean
    ) {
        val peerId = session.peerId(currentUserId)
        val directoryList = (directoryState.value as? UiState.Success)?.data.orEmpty()
        val peerEntry = directoryList.firstOrNull { it.uid == peerId }
            ?: DirectoryEntry(
                uid = peerId,
                username = session.peerUsername(currentUserId),
                phoneNumber = session.peerPhone(currentUserId),
                displayName = session.peerDisplayName(currentUserId),
                onlineStatus = false,
                discoverable = true
            )
        startOutgoingCall(peerEntry, callType, hasCameraPermission)
    }

    fun acceptIncomingCall(
        call: CallSession,
        hasCameraPermission: Boolean
    ) {
        viewModelScope.launch {
            try {
                handledIncomingCallIds.add(call.callId)
                ringtonePlayer.stop()
                _incomingCall.value = null
                resetCallInternalState()

                val enableVideo = call.parsedType() == CallType.VIDEO && hasCameraPermission
                _activeCallSession.value = call.copy(
                    status = CallStatus.ACCEPTED.wireValue,
                    calleeVideoEnabled = enableVideo
                )

                mediaManager.startSession(
                    enableVideo = enableVideo,
                    hasCameraPermission = hasCameraPermission,
                    onLocalIceCandidate = { cand ->
                        viewModelScope.launch {
                            callRepo.sendIceCandidate(
                                callId = call.callId,
                                isCaller = false,
                                participantUids = call.participantUids,
                                sdpMid = cand.sdpMid ?: "0",
                                sdpMLineIndex = cand.sdpMLineIndex,
                                candidate = cand.sdp
                            )
                        }
                    }
                )

                val offerSdp = call.offerSdp
                if (offerSdp.isNullOrBlank()) {
                    _bannerMessage.value = "Missing call offer from caller."
                    endActiveCall()
                    return@launch
                }

                val answerSdp = mediaManager.setRemoteOfferAndCreateAnswer(offerSdp)
                val acceptResult = callRepo.acceptCall(
                    callId = call.callId,
                    answerSdp = answerSdp,
                    calleeVideoEnabled = enableVideo
                )
                acceptResult.fold(
                    onSuccess = {
                        attachActiveCallListeners(call.callId, isCaller = false)
                        startDurationTimer()
                    },
                    onFailure = { err ->
                        _bannerMessage.value = err.message ?: "Failed to accept call"
                        cleanupLocalCallState()
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting call", e)
                _bannerMessage.value = e.localizedMessage ?: "Failed to connect call"
                cleanupLocalCallState()
            }
        }
    }

    fun declineIncomingCall(call: CallSession) {
        handledIncomingCallIds.add(call.callId)
        ringtonePlayer.stop()
        _incomingCall.value = null
        viewModelScope.launch {
            callRepo.declineCall(call.callId)
        }
    }

    fun endActiveCall() {
        val current = _activeCallSession.value ?: return
        val duration = _callDurationSeconds.value
        val wasNeverAnswered = current.isOutgoingFor(currentUserId) &&
            current.parsedStatus() == CallStatus.RINGING

        cleanupLocalCallState()
        viewModelScope.launch {
            callRepo.endCall(
                callId = current.callId,
                durationSeconds = duration,
                markAsMissed = wasNeverAnswered
            )
        }
    }

    fun toggleMute() {
        mediaManager.toggleMute()
    }

    fun toggleCamera(hasCameraPermission: Boolean) {
        val current = _activeCallSession.value ?: return
        val nextEnabled = !isCameraEnabled.value
        val applied = mediaManager.setCameraEnabled(nextEnabled, hasCameraPermission)
        if (nextEnabled && !applied) {
            _bannerMessage.value = "Camera permission is required to enable video."
            return
        }
        viewModelScope.launch {
            callRepo.updateVideoEnabled(
                callId = current.callId,
                isCaller = current.callerId == currentUserId,
                enabled = applied
            )
        }
    }

    fun switchCamera() {
        mediaManager.switchCamera()
    }

    fun toggleSpeakerphone() {
        mediaManager.toggleSpeakerphone()
    }

    private fun attachActiveCallListeners(callId: String, isCaller: Boolean) {
        activeCallObserverJob?.cancel()
        activeCallObserverJob = viewModelScope.launch {
            callRepo.observeCall(callId).collect { updated ->
                if (updated == null) return@collect
                _activeCallSession.value = updated

                val status = updated.parsedStatus()
                if (isCaller && status == CallStatus.ACCEPTED && !answerApplied) {
                    val answer = updated.answerSdp
                    if (!answer.isNullOrBlank()) {
                        answerApplied = true
                        ringTimeoutJob?.cancel()
                        ringtonePlayer.stop()
                        mediaManager.setRemoteAnswer(answer)
                        startDurationTimer()
                    }
                }

                if (status.isTerminal) {
                    val notice = when (status) {
                        CallStatus.DECLINED -> "${updated.peerDisplayName(currentUserId)} declined the call"
                        CallStatus.MISSED -> "No answer"
                        else -> null
                    }
                    cleanupLocalCallState()
                    if (notice != null) {
                        _bannerMessage.value = notice
                    }
                }
            }
        }

        remoteIceObserverJob?.cancel()
        remoteIceObserverJob = viewModelScope.launch {
            callRepo.observeRemoteIceCandidates(
                callId = callId,
                listenForCallerCandidates = !isCaller,
                currentUserId = currentUserId
            ).collect { candidates ->
                candidates.forEach { record ->
                    if (processedIceCandidateIds.add(record.candidateId)) {
                        mediaManager.addRemoteIceCandidate(
                            sdpMid = record.sdpMid,
                            sdpMLineIndex = record.sdpMLineIndex,
                            candidate = record.candidate
                        )
                    }
                }
            }
        }
    }

    private fun startDurationTimer() {
        if (callTimerJob?.isActive == true) return
        _callDurationSeconds.value = 0L
        callTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                _callDurationSeconds.value += 1L
            }
        }
    }

    private fun startRingTimeout(callId: String) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = viewModelScope.launch {
            delay(RING_TIMEOUT_MS)
            val current = _activeCallSession.value
            if (current != null && current.callId == callId && current.parsedStatus() == CallStatus.RINGING) {
                cleanupLocalCallState()
                callRepo.endCall(callId, durationSeconds = 0L, markAsMissed = true)
                _bannerMessage.value = "Call unanswered"
            }
        }
    }

    private fun resetCallInternalState() {
        activeCallObserverJob?.cancel()
        remoteIceObserverJob?.cancel()
        callTimerJob?.cancel()
        ringTimeoutJob?.cancel()
        processedIceCandidateIds.clear()
        answerApplied = false
        _callDurationSeconds.value = 0L
    }

    private fun cleanupLocalCallState() {
        ringtonePlayer.stop()
        resetCallInternalState()
        mediaManager.stopSession()
        _activeCallSession.value = null
    }

    suspend fun prepareSignOut() {
        ringtonePlayer.stop()
        val active = _activeCallSession.value
        if (active != null) {
            val dur = _callDurationSeconds.value
            val missed = active.isOutgoingFor(currentUserId) && active.parsedStatus() == CallStatus.RINGING
            cleanupLocalCallState()
            callRepo.endCall(active.callId, dur, markAsMissed = missed)
        }
        userRepo.updatePresence(currentUserId, online = false)
    }

    override fun onCleared() {
        super.onCleared()
        ringtonePlayer.stop()
        resetCallInternalState()
        mediaManager.dispose()
    }

    private companion object {
        const val TAG = "SelamCallVM"
        const val STOP_TIMEOUT_MS = 5000L
        const val RING_TIMEOUT_MS = 45_000L
    }
}
