package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.model.CountryCodes
import com.example.data.model.DirectoryEntry
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

class UserDirectoryRepository(
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
            ?: throw IllegalStateException("User must be signed in with Google before accessing directory.")
    }

    fun observeMyProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Refusing cross-user private profile observation." }
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserProfile::class.java,
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

    fun observeDirectory(): Flow<List<DirectoryEntry>> = flow {
        val uid = requireUserId()
        val path = "directory"
        emitAll(
            db.collection("directory")
                .whereEqualTo("discoverable", true)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DirectoryEntry::class.java,
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

    suspend fun registerDualIdentity(
        existingProfile: UserProfile?,
        displayName: String,
        rawUsername: String,
        countryCode: String,
        rawLocalPhone: String,
        discoverable: Boolean = true
    ): Result<UserProfile> {
        val uid = auth.currentUser?.uid
        val email = auth.currentUser?.email ?: existingProfile?.email ?: ""
        if (uid.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Please sign in with Google first."))
        }

        val cleanDisplayName = displayName.trim().take(60)
        val cleanUsername = CountryCodes.sanitizeUsername(rawUsername)
        val cleanCountryCode = if (countryCode.startsWith("+")) countryCode else "+$countryCode"
        val cleanLocalPhone = CountryCodes.sanitizeLocalDigits(rawLocalPhone)
        val fullPhone = "$cleanCountryCode$cleanLocalPhone"
        val newPhoneKey = CountryCodes.toPhoneClaimKey(fullPhone)

        if (cleanDisplayName.isBlank()) {
            return Result.failure(IllegalArgumentException("Display name is required."))
        }
        if (!cleanUsername.matches(Regex("^[a-z0-9_]{3,24}$"))) {
            return Result.failure(
                IllegalArgumentException("Username must be 3–24 characters using letters, numbers, or underscores.")
            )
        }
        if (!cleanLocalPhone.matches(Regex("^[0-9]{5,14}$")) || newPhoneKey.length !in 7..16) {
            return Result.failure(
                IllegalArgumentException("Please enter a valid phone number (5–14 local digits).")
            )
        }

        return try {
            // Check username uniqueness if changed or new
            if (existingProfile?.username != cleanUsername) {
                val usernameSnap = db.collection("usernames").document(cleanUsername).get().await()
                if (usernameSnap.exists() && usernameSnap.getString("uid") != uid) {
                    return Result.failure(IllegalStateException("@$cleanUsername is already taken. Try another."))
                }
            }

            // Check phone number uniqueness if changed or new
            val oldPhoneKey = existingProfile?.phoneNumber?.let { CountryCodes.toPhoneClaimKey(it) }
            if (oldPhoneKey != newPhoneKey) {
                val phoneSnap = db.collection("phone_claims").document(newPhoneKey).get().await()
                if (phoneSnap.exists() && phoneSnap.getString("uid") != uid) {
                    return Result.failure(IllegalStateException("$fullPhone is already registered to another user."))
                }
            }

            val batch = db.batch()
            val userRef = db.collection("users").document(uid)
            val dirRef = db.collection("directory").document(uid)

            // Manage username claim
            if (existingProfile != null && existingProfile.username.isNotBlank() && existingProfile.username != cleanUsername) {
                batch.delete(db.collection("usernames").document(existingProfile.username))
            }
            if (existingProfile == null || existingProfile.username != cleanUsername) {
                batch.set(
                    db.collection("usernames").document(cleanUsername),
                    mapOf("uid" to uid, "createdAt" to FieldValue.serverTimestamp())
                )
            }

            // Manage phone claim
            if (!oldPhoneKey.isNullOrBlank() && oldPhoneKey != newPhoneKey) {
                batch.delete(db.collection("phone_claims").document(oldPhoneKey))
            }
            if (oldPhoneKey != newPhoneKey) {
                batch.set(
                    db.collection("phone_claims").document(newPhoneKey),
                    mapOf("uid" to uid, "createdAt" to FieldValue.serverTimestamp())
                )
            }

            if (existingProfile == null) {
                val userPayload = mapOf(
                    "uid" to uid,
                    "email" to email.take(254),
                    "username" to cleanUsername,
                    "countryCode" to cleanCountryCode,
                    "localPhone" to cleanLocalPhone,
                    "phoneNumber" to fullPhone,
                    "displayName" to cleanDisplayName,
                    "onlineStatus" to true,
                    "discoverable" to discoverable,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val dirPayload = mapOf(
                    "uid" to uid,
                    "username" to cleanUsername,
                    "phoneNumber" to fullPhone,
                    "displayName" to cleanDisplayName,
                    "onlineStatus" to true,
                    "discoverable" to discoverable,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                batch.set(userRef, userPayload)
                batch.set(dirRef, dirPayload)
            } else {
                batch.update(
                    userRef,
                    mapOf(
                        "email" to email.take(254),
                        "username" to cleanUsername,
                        "countryCode" to cleanCountryCode,
                        "localPhone" to cleanLocalPhone,
                        "phoneNumber" to fullPhone,
                        "displayName" to cleanDisplayName,
                        "onlineStatus" to true,
                        "discoverable" to discoverable,
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    dirRef,
                    mapOf(
                        "username" to cleanUsername,
                        "phoneNumber" to fullPhone,
                        "displayName" to cleanDisplayName,
                        "onlineStatus" to true,
                        "discoverable" to discoverable,
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            batch.commit().await()

            Result.success(
                UserProfile(
                    docId = uid,
                    uid = uid,
                    email = email,
                    username = cleanUsername,
                    countryCode = cleanCountryCode,
                    localPhone = cleanLocalPhone,
                    phoneNumber = fullPhone,
                    displayName = cleanDisplayName,
                    onlineStatus = true,
                    discoverable = discoverable
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun updatePresence(userId: String, online: Boolean): Result<Unit> {
        val path = "directory/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user presence update." }
            val userDoc = db.collection("users").document(uid).get().await()
            if (!userDoc.exists()) return Result.success(Unit)

            val batch = db.batch()
            val updateFields = mapOf(
                "onlineStatus" to online,
                "lastSeen" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            batch.update(db.collection("users").document(uid), updateFields)
            batch.update(db.collection("directory").document(uid), updateFields)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }
}
