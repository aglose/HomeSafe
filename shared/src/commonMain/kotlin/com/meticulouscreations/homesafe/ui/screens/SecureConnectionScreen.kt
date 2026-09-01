package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meticulouscreations.homesafe.data.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.ConnectToServerUseCase
import com.meticulouscreations.homesafe.domain.usecase.ForgetBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveMostRecentConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveBiometricCredentialsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SignInWithBiometricsUseCase
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.FrigateExtraColors
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.ConnectUiState
import com.meticulouscreations.homesafe.viewmodel.SecureConnectionViewModel
import kotlinx.coroutines.launch

@Composable
fun SecureConnectionScreen(
    connectToServerUseCase: ConnectToServerUseCase,
    signInWithBiometricsUseCase: SignInWithBiometricsUseCase,
    saveBiometricCredentialsUseCase: SaveBiometricCredentialsUseCase,
    forgetBiometricCredentialsUseCase: ForgetBiometricCredentialsUseCase,
    observeMostRecentConnectionUseCase: ObserveMostRecentConnectionUseCase,
    connectionRepository: ConnectionRepository,
    onConnected: () -> Unit,
) {
    val viewModel = viewModel {
        SecureConnectionViewModel(
            connectToServerUseCase = connectToServerUseCase,
            signInWithBiometricsUseCase = signInWithBiometricsUseCase,
            saveBiometricCredentialsUseCase = saveBiometricCredentialsUseCase,
            forgetBiometricCredentialsUseCase = forgetBiometricCredentialsUseCase,
            observeMostRecentConnectionUseCase = observeMostRecentConnectionUseCase,
            connectionRepository = connectionRepository,
        )
    }
    val mostRecentConnection by viewModel.mostRecentConnection.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasSavedBiometricCredentials by viewModel.hasSavedBiometricCredentials.collectAsStateWithLifecycle()
    var serverUrl by remember(mostRecentConnection) {
        mutableStateOf(mostRecentConnection?.serverUrl ?: "http://frigate.local:8971")
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var biometricSaveOffer by remember { mutableStateOf<SavedCredentials?>(null) }
    val extraColors = LocalFrigateExtraColors.current
    val coroutineScope = rememberCoroutineScope()
    val isConnecting = uiState is ConnectUiState.Connecting

    // A successful login (manual or biometric) either offers to save credentials for next
    // time, or — if there's nothing new to offer — proceeds straight into the app.
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is ConnectUiState.Success) {
            if (viewModel.biometricLoginAvailable && !hasSavedBiometricCredentials) {
                biometricSaveOffer = state.credentials
            } else {
                onConnected()
            }
        }
    }

    biometricSaveOffer?.let { credentials ->
        BiometricSaveOfferDialog(
            biometricDisplayName = viewModel.biometricDisplayName,
            onSave = {
                biometricSaveOffer = null
                coroutineScope.launch {
                    viewModel.saveBiometricCredentials(credentials)
                    onConnected()
                }
            },
            onDismiss = {
                biometricSaveOffer = null
                onConnected()
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "FRIGATE",
                    style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 0.025.em),
                    color = extraColors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PulsingDot(color = MaterialTheme.colorScheme.secondary, pulsing = false)
                    Text(
                        text = "Tailscale Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Server URL input
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Server URL",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    ConnectionTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        placeholder = "Enter Server URL",
                        leadingIcon = Icons.Filled.Dns,
                        extraColors = extraColors,
                        enabled = !isConnecting,
                    )
                    Text(
                        text = "Connect to your local or remote NVR instance.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }

                // Username input
                ConnectionTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "Username",
                    leadingIcon = Icons.Filled.Person,
                    extraColors = extraColors,
                    enabled = !isConnecting,
                )

                // Password input
                ConnectionTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Password",
                    leadingIcon = Icons.Filled.Lock,
                    extraColors = extraColors,
                    enabled = !isConnecting,
                    visualTransformation = PasswordVisualTransformation(),
                )

                if (uiState is ConnectUiState.Error) {
                    Text(
                        text = (uiState as ConnectUiState.Error).message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Connect button
                Button(
                    onClick = { viewModel.connect(serverUrl, username, password) },
                    enabled = !isConnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Connect", style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }

                if (viewModel.biometricLoginAvailable && hasSavedBiometricCredentials) {
                    OutlinedButton(
                        onClick = { viewModel.signInWithBiometrics() },
                        enabled = !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = extraColors.textPrimary,
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = null)
                            Text(
                                "Sign in with ${viewModel.biometricDisplayName}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    TextButton(
                        onClick = { viewModel.forgetBiometricCredentials() },
                        enabled = !isConnecting,
                    ) {
                        Text(
                            text = "Forget saved login",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BiometricSaveOfferDialog(
    biometricDisplayName: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extraColors = LocalFrigateExtraColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = extraColors.textPrimary,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("Enable $biometricDisplayName sign-in?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "Skip retyping your password next time — sign in with $biometricDisplayName instead. " +
                    "Your credentials are encrypted and can only be unlocked with your biometrics.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text("Enable", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Not now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun ConnectionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    extraColors: FrigateExtraColors,
    enabled: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiaryContainer,
            )
        },
        visualTransformation = visualTransformation,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.tertiaryContainer,
            focusedTextColor = extraColors.textPrimary,
            unfocusedTextColor = extraColors.textPrimary,
        ),
    )
}
