package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.CountryCodeOption
import com.example.data.model.CountryCodes
import com.example.data.model.UserProfile
import com.example.ui.theme.MonoHandleStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualIdentitySetupScreen(
    existingProfile: UserProfile?,
    suggestedName: String,
    isSubmitting: Boolean,
    isEditingExisting: Boolean,
    onBack: (() -> Unit)?,
    onSignOutClick: (() -> Unit)?,
    onSave: (
        displayName: String,
        username: String,
        countryCode: String,
        localPhone: String,
        discoverable: Boolean
    ) -> Unit
) {
    if (isEditingExisting && onBack != null) {
        BackHandler { onBack() }
    }

    var displayName by remember(existingProfile) {
        mutableStateOf(
            existingProfile?.displayName?.ifBlank { suggestedName } ?: suggestedName.ifBlank { "" }
        )
    }
    var username by remember(existingProfile) {
        mutableStateOf(existingProfile?.username ?: "")
    }
    var selectedCountry by remember(existingProfile) {
        mutableStateOf(
            if (existingProfile != null) {
                CountryCodes.findByDialCode(existingProfile.countryCode)
            } else {
                CountryCodes.defaultCountry
            }
        )
    }
    var localPhone by remember(existingProfile) {
        mutableStateOf(existingProfile?.localPhone ?: "")
    }
    var discoverable by remember(existingProfile) {
        mutableStateOf(existingProfile?.discoverable ?: true)
    }
    var showCountryPicker by remember { mutableStateOf(false) }

    val cleanUsername = CountryCodes.sanitizeUsername(username)
    val cleanLocalDigits = CountryCodes.sanitizeLocalDigits(localPhone)
    val fullE164 = CountryCodes.formatE164(selectedCountry.dialCode, localPhone)
    val isUsernameValid = cleanUsername.matches(Regex("^[a-z0-9_]{3,24}$"))
    val isPhoneValid = cleanLocalDigits.matches(Regex("^[0-9]{5,14}$"))
    val isNameValid = displayName.trim().length in 1..60
    val canSubmit = isUsernameValid && isPhoneValid && isNameValid && !isSubmitting

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            if (isEditingExisting && onBack != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Text(
                        text = "Edit Dual Identity",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            } else {
                Text(
                    text = "Set Up Your Dual Identity",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Friends can reach you instantly by either your Phone Number or your unique @username.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { if (it.length <= 60) displayName = it },
                label = { Text("Display Name") },
                placeholder = { Text("e.g. Chala Mohammed") },
                leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("identity_display_name_input")
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { raw ->
                    val stripped = raw.removePrefix("@").lowercase()
                    if (stripped.length <= 24) username = stripped
                },
                label = { Text("Unique @username") },
                placeholder = { Text("e.g. chala") },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, contentDescription = null) },
                supportingText = {
                    val hint = if (username.isEmpty() || isUsernameValid) {
                        "3–24 chars: lowercase letters, digits, or underscore (_)"
                    } else {
                        "Use 3–24 lowercase letters, digits, or underscores only"
                    }
                    Text(hint)
                },
                isError = username.isNotEmpty() && !isUsernameValid,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("identity_username_input")
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Phone Number",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                OutlinedButton(
                    onClick = { showCountryPicker = true },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .height(56.dp)
                        .testTag("country_code_selector")
                ) {
                    Text(
                        text = "${selectedCountry.flagEmoji} ${selectedCountry.dialCode}",
                        style = MonoHandleStyle,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "Select country code",
                        modifier = Modifier.size(18.dp)
                    )
                }

                OutlinedTextField(
                    value = localPhone,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }
                        if (digits.length <= 14) localPhone = digits
                    },
                    label = { Text("Phone Number") },
                    placeholder = { Text("983156394") },
                    leadingIcon = { Icon(Icons.Rounded.Phone, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    isError = localPhone.isNotEmpty() && !isPhoneValid,
                    supportingText = {
                        if (isPhoneValid) {
                            Text("Callable ID: $fullE164", style = MonoHandleStyle)
                        } else {
                            Text("Default +251 Ethiopia • Enter 5–14 digits")
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("identity_phone_input")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Discoverable in Directory",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Allow signed-in users to find you by @username or phone number",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = discoverable,
                        onCheckedChange = { discoverable = it },
                        modifier = Modifier.testTag("discoverable_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    onSave(
                        displayName.trim(),
                        cleanUsername,
                        selectedCountry.dialCode,
                        cleanLocalDigits,
                        discoverable
                    )
                },
                enabled = canSubmit,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("save_identity_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Claiming Identity...")
                } else {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isEditingExisting) "Save Changes" else "Complete Setup & Enter",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (!isEditingExisting && onSignOutClick != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSignOutClick,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Switch Google Account")
                }
            }
        }
    }

    if (showCountryPicker) {
        CountryPickerSheet(
            selected = selectedCountry,
            onSelect = {
                selectedCountry = it
                showCountryPicker = false
            },
            onDismiss = { showCountryPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPickerSheet(
    selected: CountryCodeOption,
    onSelect: (CountryCodeOption) -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(filter) {
        val q = filter.trim().lowercase()
        if (q.isBlank()) CountryCodes.all
        else CountryCodes.all.filter {
            it.name.lowercase().contains(q) ||
                it.dialCode.contains(q) ||
                it.isoCode.lowercase().contains(q)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Select Country Code",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Search country or +code...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.height(340.dp)) {
                items(filtered, key = { "${it.isoCode}_${it.dialCode}" }) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = option.flagEmoji, style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Text(
                            text = option.dialCode,
                            style = MonoHandleStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}
