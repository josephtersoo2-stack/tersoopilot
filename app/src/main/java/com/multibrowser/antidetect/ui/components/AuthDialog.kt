package com.multibrowser.antidetect.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.multibrowser.antidetect.network.*
import com.multibrowser.antidetect.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthDialog(
    onDismiss: () -> Unit,
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isRegisterMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorCode by remember { mutableStateOf<String?>(null) }

    var showServerConfig by remember { mutableStateOf(false) }
    var serverHostInput by remember { mutableStateOf(AuthManager.getServerHost()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = OctoSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isRegisterMode) "Create Account" else "Sign In",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OctoTextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = OctoTextMuted)
                    }
                }

                Text(
                    if (isRegisterMode)
                        "Register to sync your profiles, history, cookies, and tabs to the cloud."
                    else
                        "Sign in to access your cloud-saved profiles and restore your browser sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OctoTextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                // Mode Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OctoSurface, RoundedCornerShape(10.dp))
                        .padding(4.dp)
                ) {
                    Button(
                        onClick = {
                            isRegisterMode = false
                            errorMessage = null
                            errorCode = null
                            confirmPassword = ""
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isRegisterMode) OctoPrimary else Color.Transparent,
                            contentColor = if (!isRegisterMode) Color.White else OctoTextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sign In", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            isRegisterMode = true
                            errorMessage = null
                            errorCode = null
                            confirmPassword = ""
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRegisterMode) OctoPrimary else Color.Transparent,
                            contentColor = if (isRegisterMode) Color.White else OctoTextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Register", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Username field
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                        errorCode = null
                    },
                    label = { Text("Username") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = OctoPrimary) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = octoTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                AnimatedVisibility(visible = isRegisterMode) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email (Optional)") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = OctoPrimary) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = octoTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Password field with view toggle
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                        errorCode = null
                    },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = OctoPrimary) },
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        val description = if (passwordVisible) "Hide password" else "Show password"
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(image, contentDescription = description, tint = OctoTextMuted)
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = octoTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Double password field (Confirm Password) for Registration with view toggle
                AnimatedVisibility(visible = isRegisterMode) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = {
                                confirmPassword = it
                                errorMessage = null
                                errorCode = null
                            },
                            label = { Text("Confirm Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = OctoPrimary) },
                            trailingIcon = {
                                val image = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                                val description = if (confirmPasswordVisible) "Hide password" else "Show password"
                                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                    Icon(image, contentDescription = description, tint = OctoTextMuted)
                                }
                            },
                            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = octoTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Error alert with smart helper
                AnimatedVisibility(visible = errorMessage != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .background(OctoDanger.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = OctoDanger,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )

                        if (errorCode == "USER_NOT_FOUND") {
                            Spacer(modifier = Modifier.height(6.dp))
                            FilledTonalButton(
                                onClick = {
                                    isRegisterMode = true
                                    errorMessage = null
                                    errorCode = null
                                    confirmPassword = password
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = OctoPrimary.copy(alpha = 0.25f),
                                    contentColor = OctoPrimary
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Switch to Register & Create Account", fontWeight = FontWeight.Bold)
                            }
                        } else if (errorCode == "USER_ALREADY_EXISTS") {
                            Spacer(modifier = Modifier.height(6.dp))
                            FilledTonalButton(
                                onClick = {
                                    isRegisterMode = false
                                    errorMessage = null
                                    errorCode = null
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = OctoPrimary.copy(alpha = 0.25f),
                                    contentColor = OctoPrimary
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Switch to Sign In", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Button
                Button(
                    onClick = {
                        val u = username.trim()
                        val p = password.trim()
                        val cp = confirmPassword.trim()
                        val e = email.trim()

                        if (u.isEmpty() || p.isEmpty()) {
                            errorMessage = "Please enter username and password."
                            errorCode = "MISSING_FIELDS"
                            return@Button
                        }
                        if (p.length < 4) {
                            errorMessage = "Password must be at least 4 characters long."
                            errorCode = "PASSWORD_TOO_SHORT"
                            return@Button
                        }
                        if (isRegisterMode) {
                            if (cp.isEmpty()) {
                                errorMessage = "Please confirm your password."
                                errorCode = "CONFIRM_PASSWORD_EMPTY"
                                return@Button
                            }
                            if (p != cp) {
                                errorMessage = "Passwords do not match."
                                errorCode = "PASSWORDS_DO_NOT_MATCH"
                                return@Button
                            }
                        }

                        isLoading = true
                        errorMessage = null
                        errorCode = null

                        coroutineScope.launch {
                            try {
                                val resp = withContext(Dispatchers.IO) {
                                    if (isRegisterMode) {
                                        RetrofitInstance.api.register(RegisterRequest(u, p, e))
                                    } else {
                                        RetrofitInstance.api.login(LoginRequest(u, p))
                                    }
                                }
                                AuthManager.saveAuth(resp.token, resp.user)
                                Toast.makeText(
                                    context,
                                    if (isRegisterMode) "Account created! Signed in as ${resp.user.username}" else "Welcome back, ${resp.user.username}!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onAuthSuccess()
                                onDismiss()
                            } catch (err: Exception) {
                                if (err is HttpException) {
                                    try {
                                        val errorBody = err.response()?.errorBody()?.string()
                                        if (!errorBody.isNullOrBlank()) {
                                            val json = JSONObject(errorBody)
                                            errorMessage = json.optString("error").ifBlank {
                                                json.optString("detail").ifBlank { json.optString("message") }
                                            }
                                            errorCode = json.optString("code").ifBlank { null }
                                        }
                                    } catch (_: Exception) {}

                                    if (errorMessage.isNullOrBlank()) {
                                        errorMessage = when (err.code()) {
                                            400 -> if (!isRegisterMode)
                                                "Account not found or invalid credentials. If new, please tap 'Register'."
                                            else
                                                "Invalid registration details. Please verify your username and password."
                                            401 -> "Invalid username or password."
                                            404 -> "Authentication endpoint not found on backend."
                                            500 -> "Server internal error. Please check backend logs."
                                            else -> "HTTP ${err.code()}: ${err.message()}"
                                        }
                                    }
                                } else {
                                    errorMessage = err.localizedMessage ?: "Network error. Failed to reach backend."
                                }
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = OctoPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            if (isRegisterMode) "Create Account & Sign In" else "Sign In",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Server Configuration Row
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showServerConfig = !showServerConfig }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = OctoTextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Backend: ${RetrofitInstance.activeHost}:8000",
                            style = MaterialTheme.typography.labelSmall,
                            color = OctoTextMuted
                        )
                    }
                    Text(
                        if (showServerConfig) "Done" else "Configure",
                        style = MaterialTheme.typography.labelSmall,
                        color = OctoPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                AnimatedVisibility(visible = showServerConfig) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(OctoSurface, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            "Backend Server IP / Host",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OctoTextPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = serverHostInput,
                            onValueChange = {
                                serverHostInput = it
                                AuthManager.saveServerHost(it)
                            },
                            placeholder = { Text("10.84.158.87 or 127.0.0.1") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = octoTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = {
                                    serverHostInput = "10.84.158.87"
                                    AuthManager.saveServerHost("10.84.158.87")
                                },
                                label = { Text("USB: 10.84.158.87", style = MaterialTheme.typography.labelSmall) }
                            )
                            AssistChip(
                                onClick = {
                                    serverHostInput = "127.0.0.1"
                                    AuthManager.saveServerHost("127.0.0.1")
                                },
                                label = { Text("ADB: 127.0.0.1", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // Footer Toggle Helper
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isRegisterMode) "Already have an account? " else "Don't have an account yet? ",
                        style = MaterialTheme.typography.bodySmall,
                        color = OctoTextSecondary
                    )
                    Text(
                        if (isRegisterMode) "Sign In" else "Register now",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = OctoPrimary,
                        modifier = Modifier.clickable {
                            isRegisterMode = !isRegisterMode
                            errorMessage = null
                            errorCode = null
                            confirmPassword = ""
                        }
                    )
                }
            }
        }
    }
}
