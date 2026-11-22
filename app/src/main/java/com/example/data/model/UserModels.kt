package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val docId: String = "",
    val uid: String = "",
    val email: String = "",
    val username: String = "",
    val countryCode: String = "+251",
    val localPhone: String = "",
    val phoneNumber: String = "",
    val displayName: String = "",
    val onlineStatus: Boolean = false,
    val discoverable: Boolean = true,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DirectoryEntry(
    @DocumentId val docId: String = "",
    val uid: String = "",
    val username: String = "",
    val phoneNumber: String = "",
    val displayName: String = "",
    val onlineStatus: Boolean = false,
    val discoverable: Boolean = true,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun initials(): String {
        val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
            parts.size == 1 -> parts.first().take(2).uppercase()
            username.isNotBlank() -> username.take(2).uppercase()
            else -> "SC"
        }
    }
}
