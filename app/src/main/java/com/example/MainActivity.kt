package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.firebase.AppThemeMode
import com.example.data.firebase.CallSignalingRepository
import com.example.data.firebase.ThemePreferencesRepository
import com.example.data.firebase.UserDirectoryRepository
import com.example.data.firebase.signOut
import com.example.data.model.CallSession
import com.example.data.model.CallType
import com.example.data.model.DirectoryEntry
import com.example.ui.components.PermissionPromptDialog
import com.example.ui.screens.ActiveCallScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallHistoryScreen
import com.example.ui.screens.DirectoryScreen
import com.example.ui.screens.DualIdentitySetupScreen
import com.example.ui.screens.IncomingCallOverlay
import com.example.ui.screens.SupportAboutScreen
import com.example.ui.theme.SelamCallTheme
import com.example.ui.viewmodel.SelamCallViewModel
import com.example.ui.viewmodel.UiState
import com.example.webrtc.RingtonePlayer
import com.example.webrtc.WebRtcMediaManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

object Routes {
    const val ROUTE_CONTACTS = "contacts"
    const val ROUTE_RECENTS = "recents"
    const val ROUTE_SUPPORT = "support"
}

private sealed interface PendingMediaAction {
    data class OutgoingCall(val callee: DirectoryEntry, val callType: CallType) : PendingMediaAction
    data class RedialCall(val session: CallSession, val callType: CallType) : PendingMediaAction
    data class AcceptCall(val session: CallSession) : PendingMediaAction
    data object EnableCameraInCall : PendingMediaAction
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val appScope = rememberCoroutineScope()
            val themeRepo = remember(context) { ThemePreferencesRepository(context) }
            val themeMode by themeRepo.themeMode.collectAsStateWithLifecycle(initialValue = AppThemeMode.SYSTEM)
            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemDark
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }

            SelamCallTheme(darkTheme = darkTheme) {
                SelamCallRoot(
                    themeMode = themeMode,
                    onSelectThemeMode = { mode ->
                        appScope.launch {
                            themeRepo.setThemeMode(mode)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SelamCallRoot(
    themeMode: AppThemeMode,
    onSelectThemeMode: (AppThemeMode) -> Unit
) {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(
            onAuthSuccess = {
                currentUser = Firebase.auth.currentUser
            }
        )
    } else {
        val uid = user.uid
        key(uid) {
            AuthenticatedAppContent(
                currentUserId = uid,
                userEmail = user.email.orEmpty(),
                googleDisplayName = user.displayName.orEmpty(),
                themeMode = themeMode,
                onSelectThemeMode = onSelectThemeMode,
                onSignedOut = { currentUser = null }
            )
        }
    }
}

@Composable
private fun AuthenticatedAppContent(
    currentUserId: String,
    userEmail: String,
    googleDisplayName: String,
    themeMode: AppThemeMode,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: SelamCallViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val dbId = appContext.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(dbId)
                SelamCallViewModel(
                    currentUserId = currentUserId,
                    userEmail = userEmail,
                    googleDisplayName = googleDisplayName,
                    userRepo = UserDirectoryRepository(db),
                    callRepo = CallSignalingRepository(db),
                    mediaManager = WebRtcMediaManager(appContext),
                    ringtonePlayer = RingtonePlayer(appContext)
                )
            }
        }
    )

    // Lifecycle online/offline presence tracking
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentUserId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setOnlineStatus(true)
                Lifecycle.Event.ON_STOP -> viewModel.setOnlineStatus(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val myProfileState by viewModel.myProfileState.collectAsStateWithLifecycle()
    val directoryState by viewModel.filteredDirectoryState.collectAsStateWithLifecycle()
    val historyState by viewModel.filteredCallHistoryState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val onlineOnly by viewModel.onlineOnlyFilter.collectAsStateWithLifecycle()
    val historyFilter by viewModel.historyFilter.collectAsStateWithLifecycle()
    val incomingCall by viewModel.incomingCall.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCallSession.collectAsStateWithLifecycle()
    val isSubmittingProfile by viewModel.isSubmittingProfile.collectAsStateWithLifecycle()
    val isEditingProfile by viewModel.isEditingProfile.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isCameraEnabled by viewModel.isCameraEnabled.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val durationSeconds by viewModel.callDurationSeconds.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    // Runtime permission handling for Microphone & Camera
    var pendingAction by remember { mutableStateOf<PendingMediaAction?>(null) }
    var permissionDialogNeedsCamera by remember { mutableStateOf(false) }
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission()
        val cameraGranted = results[Manifest.permission.CAMERA] ?: hasCameraPermission()
        val action = pendingAction
        val needsCam = action is PendingMediaAction.EnableCameraInCall ||
            (action is PendingMediaAction.OutgoingCall && action.callType == CallType.VIDEO) ||
            (action is PendingMediaAction.RedialCall && action.callType == CallType.VIDEO) ||
            (action is PendingMediaAction.AcceptCall && action.session.parsedType() == CallType.VIDEO)

        if (audioGranted && (!needsCam || cameraGranted)) {
            pendingAction = null
            when (action) {
                is PendingMediaAction.OutgoingCall ->
                    viewModel.startOutgoingCall(action.callee, action.callType, hasCameraPermission())
                is PendingMediaAction.RedialCall ->
                    viewModel.redialFromHistory(action.session, action.callType, hasCameraPermission())
                is PendingMediaAction.AcceptCall ->
                    viewModel.acceptIncomingCall(action.session, hasCameraPermission())
                is PendingMediaAction.EnableCameraInCall ->
                    viewModel.toggleCamera(hasCameraPermission())
                null -> Unit
            }
        } else {
            val activity = context as? android.app.Activity
            val audioDeniedForever = !audioGranted && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            val camDeniedForever = needsCam && !cameraGranted && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            permissionDialogNeedsCamera = needsCam
            permissionPermanentlyDenied = audioDeniedForever || camDeniedForever
            showPermissionDialog = true
        }
    }

    fun executeWithPermissions(action: PendingMediaAction) {
        val needsCam = when (action) {
            is PendingMediaAction.OutgoingCall -> action.callType == CallType.VIDEO
            is PendingMediaAction.RedialCall -> action.callType == CallType.VIDEO
            is PendingMediaAction.AcceptCall -> action.session.parsedType() == CallType.VIDEO
            is PendingMediaAction.EnableCameraInCall -> true
        }
        val audioOk = hasAudioPermission()
        val camOk = !needsCam || hasCameraPermission()
        if (audioOk && camOk) {
            when (action) {
                is PendingMediaAction.OutgoingCall ->
                    viewModel.startOutgoingCall(action.callee, action.callType, hasCameraPermission())
                is PendingMediaAction.RedialCall ->
                    viewModel.redialFromHistory(action.session, action.callType, hasCameraPermission())
                is PendingMediaAction.AcceptCall ->
                    viewModel.acceptIncomingCall(action.session, hasCameraPermission())
                is PendingMediaAction.EnableCameraInCall ->
                    viewModel.toggleCamera(hasCameraPermission())
            }
        } else {
            pendingAction = action
            val perms = buildList {
                if (!audioOk) add(Manifest.permission.RECORD_AUDIO)
                if (needsCam && !hasCameraPermission()) add(Manifest.permission.CAMERA)
            }.toTypedArray()
            permissionLauncher.launch(perms)
        }
    }

    val performSignOut: () -> Unit = {
        signOut(
            credentialManager = credentialManager,
            scope = scope,
            beforeSignOut = { viewModel.prepareSignOut() },
            onSignOutComplete = onSignedOut
        )
    }

    // Root state routing
    when (val profileState = myProfileState) {
        is UiState.Loading -> {
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        is UiState.Error -> {
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profileState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        is UiState.Success -> {
            val profile = profileState.data
            if (profile == null || isEditingProfile) {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
                        DualIdentitySetupScreen(
                            existingProfile = profile,
                            suggestedName = viewModel.suggestedDisplayName(),
                            isSubmitting = isSubmittingProfile,
                            isEditingExisting = profile != null && isEditingProfile,
                            onBack = if (profile != null) ({ viewModel.closeEditProfile() }) else null,
                            onSignOutClick = if (profile == null) performSignOut else null,
                            onSave = { displayName, username, countryCode, localPhone, discoverable ->
                                viewModel.registerOrUpdateIdentity(
                                    displayName = displayName,
                                    rawUsername = username,
                                    countryCode = countryCode,
                                    rawLocalPhone = localPhone,
                                    discoverable = discoverable
                                )
                            }
                        )
                    }
                }
            } else {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                val contactsLabel = stringResource(R.string.nav_contacts)
                val recentsLabel = stringResource(R.string.nav_recents)
                val supportLabel = stringResource(R.string.nav_support)

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useNavRail = maxWidth >= 600.dp

                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            if (!useNavRail) {
                                NavigationBar {
                                    val items = listOf(
                                        Triple(Routes.ROUTE_CONTACTS, contactsLabel, Pair(Icons.Filled.Contacts, Icons.Outlined.Contacts)),
                                        Triple(Routes.ROUTE_RECENTS, recentsLabel, Pair(Icons.Filled.History, Icons.Outlined.History)),
                                        Triple(Routes.ROUTE_SUPPORT, supportLabel, Pair(Icons.Filled.SupportAgent, Icons.Outlined.SupportAgent))
                                    )
                                    items.forEach { (route, label, icons) ->
                                        val selected = currentDestination?.hierarchy?.any { it.route == route } == true
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = {
                                                navController.navigate(route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) icons.first else icons.second,
                                                    contentDescription = label
                                                )
                                            },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("nav_tab_$route")
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (useNavRail) {
                                NavigationRail {
                                    val items = listOf(
                                        Triple(Routes.ROUTE_CONTACTS, contactsLabel, Pair(Icons.Filled.Contacts, Icons.Outlined.Contacts)),
                                        Triple(Routes.ROUTE_RECENTS, recentsLabel, Pair(Icons.Filled.History, Icons.Outlined.History)),
                                        Triple(Routes.ROUTE_SUPPORT, supportLabel, Pair(Icons.Filled.SupportAgent, Icons.Outlined.SupportAgent))
                                    )
                                    items.forEach { (route, label, icons) ->
                                        val selected = currentDestination?.hierarchy?.any { it.route == route } == true
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = {
                                                navController.navigate(route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) icons.first else icons.second,
                                                    contentDescription = label
                                                )
                                            },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("rail_tab_$route")
                                        )
                                    }
                                }
                            }

                            NavHost(
                                navController = navController,
                                startDestination = Routes.ROUTE_CONTACTS,
                                modifier = Modifier.weight(1f)
                            ) {
                                composable(Routes.ROUTE_CONTACTS) {
                                    DirectoryScreen(
                                        myProfile = profile,
                                        directoryState = directoryState,
                                        searchQuery = searchQuery,
                                        onlineOnly = onlineOnly,
                                        onSearchChange = viewModel::updateSearchQuery,
                                        onToggleOnlineOnly = viewModel::toggleOnlineOnlyFilter,
                                        onEditProfile = viewModel::openEditProfile,
                                        onCopyFeedback = viewModel::showBannerMessage,
                                        onAudioCall = { entry ->
                                            executeWithPermissions(PendingMediaAction.OutgoingCall(entry, CallType.AUDIO))
                                        },
                                        onVideoCall = { entry ->
                                            executeWithPermissions(PendingMediaAction.OutgoingCall(entry, CallType.VIDEO))
                                        }
                                    )
                                }

                                composable(Routes.ROUTE_RECENTS) {
                                    CallHistoryScreen(
                                        currentUserId = currentUserId,
                                        historyState = historyState,
                                        selectedFilter = historyFilter,
                                        onSelectFilter = viewModel::selectHistoryFilter,
                                        onRedial = { session, callType ->
                                            executeWithPermissions(PendingMediaAction.RedialCall(session, callType))
                                        }
                                    )
                                }

                                composable(Routes.ROUTE_SUPPORT) {
                                    SupportAboutScreen(
                                        myProfile = profile,
                                        themeMode = themeMode,
                                        onSelectThemeMode = onSelectThemeMode,
                                        onEditProfile = viewModel::openEditProfile,
                                        onSignOut = performSignOut,
                                        onCopyFeedback = viewModel::showBannerMessage
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Active Call Full-Screen Overlay
    val currentActive = activeCall
    if (currentActive != null) {
        ActiveCallScreen(
            currentUserId = currentUserId,
            session = currentActive,
            eglBaseContext = viewModel.eglBaseContext,
            localVideoTrack = localVideoTrack,
            remoteVideoTrack = remoteVideoTrack,
            isMuted = isMuted,
            isCameraEnabled = isCameraEnabled,
            isSpeakerOn = isSpeakerOn,
            isFrontCamera = isFrontCamera,
            connectionState = connectionState,
            durationSeconds = durationSeconds,
            onToggleMute = viewModel::toggleMute,
            onToggleCamera = {
                if (!isCameraEnabled && !hasCameraPermission()) {
                    executeWithPermissions(PendingMediaAction.EnableCameraInCall)
                } else {
                    viewModel.toggleCamera(hasCameraPermission())
                }
            },
            onSwitchCamera = viewModel::switchCamera,
            onToggleSpeaker = viewModel::toggleSpeakerphone,
            onEndCall = viewModel::endActiveCall
        )
    }

    // Incoming Call Ringing Overlay
    val ringing = incomingCall
    if (ringing != null && currentActive == null) {
        IncomingCallOverlay(
            call = ringing,
            onAccept = {
                executeWithPermissions(PendingMediaAction.AcceptCall(ringing))
            },
            onDecline = {
                viewModel.declineIncomingCall(ringing)
            }
        )
    }

    // Permission Rationale Dialog
    if (showPermissionDialog) {
        PermissionPromptDialog(
            needsCamera = permissionDialogNeedsCamera,
            permanentlyDenied = permissionPermanentlyDenied,
            onRequestAgain = {
                showPermissionDialog = false
                val perms = if (permissionDialogNeedsCamera) {
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                } else {
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                }
                permissionLauncher.launch(perms)
            },
            onDismiss = {
                showPermissionDialog = false
                pendingAction = null
            }
        )
    }
}
