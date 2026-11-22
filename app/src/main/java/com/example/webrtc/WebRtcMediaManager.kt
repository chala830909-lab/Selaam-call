package com.example.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

enum class WebRtcConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}

class WebRtcMediaManager(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val eglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrackInstance: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var remoteDescriptionSet = false
    private val pendingIceCandidates = CopyOnWriteArrayList<IceCandidate>()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _connectionState = MutableStateFlow(WebRtcConnectionState.IDLE)
    val connectionState: StateFlow<WebRtcConnectionState> = _connectionState.asStateFlow()

    private var onLocalIceCandidateCallback: ((IceCandidate) -> Unit)? = null

    init {
        ensureFactoryInitialized(appContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startSession(
        enableVideo: Boolean,
        hasCameraPermission: Boolean,
        onLocalIceCandidate: (IceCandidate) -> Unit
    ) {
        stopSession()
        onLocalIceCandidateCallback = onLocalIceCandidate
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        _connectionState.value = WebRtcConnectionState.CONNECTING
        _isMuted.value = false

        configureAudioRouting(speakerOn = enableVideo)
        createPeerConnection()
        setupLocalAudio()

        if (hasCameraPermission) {
            setupLocalVideo(initiallyEnabled = enableVideo)
        } else {
            _isCameraEnabled.value = false
        }
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    onLocalIceCandidateCallback?.invoke(candidate)
                }

                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        _remoteVideoTrack.value = track
                    }
                }

                override fun onAddStream(stream: MediaStream?) {
                    val video = stream?.videoTracks?.firstOrNull()
                    if (video != null) {
                        _remoteVideoTrack.value = video
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            _connectionState.value = WebRtcConnectionState.CONNECTED
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            _connectionState.value = WebRtcConnectionState.DISCONNECTED
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            _connectionState.value = WebRtcConnectionState.FAILED
                        }
                        PeerConnection.PeerConnectionState.CONNECTING -> {
                            _connectionState.value = WebRtcConnectionState.CONNECTING
                        }
                        else -> Unit
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    if (newState == PeerConnection.IceConnectionState.CONNECTED ||
                        newState == PeerConnection.IceConnectionState.COMPLETED
                    ) {
                        _connectionState.value = WebRtcConnectionState.CONNECTED
                    } else if (newState == PeerConnection.IceConnectionState.FAILED) {
                        _connectionState.value = WebRtcConnectionState.FAILED
                    }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )
    }

    private fun setupLocalAudio() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = peerConnectionFactory.createAudioSource(constraints)
        val track = peerConnectionFactory.createAudioTrack("SELAM_AUDIO_0", source)
        track.setEnabled(true)
        audioSource = source
        localAudioTrack = track
        peerConnection?.addTrack(track, listOf("SELAM_STREAM"))
    }

    private fun setupLocalVideo(initiallyEnabled: Boolean) {
        try {
            val enumerator = Camera2Enumerator(appContext)
            val deviceNames = enumerator.deviceNames
            val frontName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: deviceNames.firstOrNull()
            if (frontName == null) {
                Log.w(TAG, "No camera devices found; continuing with audio only.")
                _isCameraEnabled.value = false
                return
            }

            val capturer = enumerator.createCapturer(frontName, null)
            if (capturer == null) {
                Log.w(TAG, "Could not create CameraVideoCapturer.")
                _isCameraEnabled.value = false
                return
            }

            val helper = SurfaceTextureHelper.create("WebRtcCaptureThread", eglBase.eglBaseContext)
            val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, appContext, source.capturerObserver)
            if (initiallyEnabled) {
                capturer.startCapture(720, 1280, 30)
            }

            val track = peerConnectionFactory.createVideoTrack("SELAM_VIDEO_0", source)
            track.setEnabled(initiallyEnabled)

            videoCapturer = capturer
            surfaceTextureHelper = helper
            videoSource = source
            localVideoTrackInstance = track
            _localVideoTrack.value = track
            _isFrontCamera.value = enumerator.isFrontFacing(frontName)
            _isCameraEnabled.value = initiallyEnabled

            peerConnection?.addTrack(track, listOf("SELAM_STREAM"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize local camera", e)
            _isCameraEnabled.value = false
        }
    }

    suspend fun createOfferSdp(): String {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val offer = suspendCoroutine<SessionDescription> { cont ->
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    cont.resume(sdp)
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "Failed to create SDP offer"))
                }
            }, constraints)
        }
        suspendCoroutine<Unit> { cont ->
            pc.setLocalDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "Failed to set local offer"))
                }
            }, offer)
        }
        return offer.description
    }

    suspend fun setRemoteOfferAndCreateAnswer(offerSdp: String): String {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        suspendCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingCandidates()
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "Failed to set remote offer"))
                }
            }, remoteOffer)
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val answer = suspendCoroutine<SessionDescription> { cont ->
            pc.createAnswer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    cont.resume(sdp)
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "Failed to create SDP answer"))
                }
            }, constraints)
        }
        suspendCoroutine<Unit> { cont ->
            pc.setLocalDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "Failed to set local answer"))
                }
            }, answer)
        }
        return answer.description
    }

    suspend fun setRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: return
        if (remoteDescriptionSet) return
        val remoteAnswer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        suspendCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingCandidates()
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "Failed to set remote answer"))
                }
            }, remoteAnswer)
        }
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(ice)
        } else {
            pendingIceCandidates.add(ice)
        }
    }

    private fun flushPendingCandidates() {
        val pc = peerConnection ?: return
        pendingIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    fun toggleMute(): Boolean {
        val nextMuted = !_isMuted.value
        localAudioTrack?.setEnabled(!nextMuted)
        _isMuted.value = nextMuted
        return nextMuted
    }

    fun setCameraEnabled(enabled: Boolean, hasCameraPermission: Boolean): Boolean {
        if (enabled && !hasCameraPermission) {
            return false
        }
        if (enabled && videoCapturer == null) {
            setupLocalVideo(initiallyEnabled = true)
            return _isCameraEnabled.value
        }
        try {
            if (enabled) {
                videoCapturer?.startCapture(720, 1280, 30)
            } else {
                videoCapturer?.stopCapture()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera capture toggle warning: ${e.message}")
        }
        localVideoTrackInstance?.setEnabled(enabled)
        _isCameraEnabled.value = enabled
        return enabled
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _isFrontCamera.value = isFrontCamera
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w(TAG, "Camera switch error: $errorDescription")
            }
        })
    }

    fun toggleSpeakerphone(): Boolean {
        val next = !_isSpeakerOn.value
        configureAudioRouting(speakerOn = next)
        return next
    }

    private fun configureAudioRouting(speakerOn: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val targetType = if (speakerOn) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = devices.firstOrNull { it.type == targetType }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                } else if (!speakerOn) {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = speakerOn
            }
            _isSpeakerOn.value = speakerOn
        } catch (e: Exception) {
            Log.w(TAG, "Audio routing warning: ${e.message}")
        }
    }

    fun stopSession() {
        onLocalIceCandidateCallback = null
        remoteDescriptionSet = false
        pendingIceCandidates.clear()

        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            videoCapturer?.dispose()
        } catch (_: Exception) {
        }
        videoCapturer = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        surfaceTextureHelper = null

        localVideoTrackInstance?.dispose()
        localVideoTrackInstance = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null

        videoSource?.dispose()
        videoSource = null

        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }

        _isCameraEnabled.value = false
        _isMuted.value = false
        _isSpeakerOn.value = false
        _connectionState.value = WebRtcConnectionState.IDLE
    }

    fun dispose() {
        stopSession()
        peerConnectionFactory.dispose()
        eglBase.release()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    private companion object {
        const val TAG = "WebRtcMediaManager"

        @Volatile
        private var factoryInitialized = false

        fun ensureFactoryInitialized(context: Context) {
            if (!factoryInitialized) {
                synchronized(this) {
                    if (!factoryInitialized) {
                        val options = PeerConnectionFactory.InitializationOptions.builder(context)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                        PeerConnectionFactory.initialize(options)
                        factoryInitialized = true
                    }
                }
            }
        }
    }
}
