package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.local.UserAccountEntity
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.BiometricAuthHelper
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Interactive Google Sign-In flow via Credential Manager and GetSignInWithGoogleOption.
 */
fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id).trim()
    } catch (e: Exception) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }
    if (clientId.isBlank()) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id is empty")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()

    scope.launch {
        try {
            val activityContext = context as? Activity ?: context
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("نوع بيانات الاعتماد غير مدعوم")
            }
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            Log.w("Auth", "No Google account credentials found on device", e)
            onAuthError("لا يوجد حساب Google متاح على هذا الهاتف. يرجى تسجيل الدخول باسم المستخدم وكلمة المرور أو إضافة حسابك في إعدادات الهاتف.")
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            val friendlyMsg = com.example.util.AuthErrorHandler.handleAuthError(context, e, showToastNotification = true)
            onAuthError(friendlyMsg)
        }
    }
}

/**
 * Full Sign-Out helper clearing both Firebase Auth and Credential Manager state.
 */
fun signOutFromFirebaseAndCredentials(
    context: Context,
    onSignOutComplete: () -> Unit,
    scope: CoroutineScope
) {
    val credentialManager = CredentialManager.create(context)
    Firebase.auth.signOut()
    scope.launch {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.e("Auth", "Failed to clear credential state", e)
        } finally {
            onSignOutComplete()
        }
    }
}

@Composable
fun LoginScreen(
    viewModel: PmpViewModel,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAuthTab by remember { mutableIntStateOf(0) }
    var emailInput by remember { mutableStateOf("") }
    var usernameInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingApprovalBanner by remember { mutableStateOf<String?>(null) }
    var isCredentialsSubmitting by remember { mutableStateOf(false) }
    var isCheckingApproval by remember { mutableStateOf(false) }

    // State for Forgot Password & Account Recovery Modal
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var recoveryMode by remember { mutableIntStateOf(0) } // 0 = OTP, 1 = Backup Recovery Code
    var resetIdentityInput by remember { mutableStateOf("") }
    var resetChannelChoice by remember { mutableStateOf("EMAIL") } // "EMAIL" or "MANAGER_ALERT"
    var resetStep by remember { mutableIntStateOf(1) } // 1 = request, 2 = verify and reset
    var generatedResetCode by remember { mutableStateOf("") }
    var enteredResetCode by remember { mutableStateOf("") }
    var backupRecoveryCodeInput by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var targetResetUser by remember { mutableStateOf<UserAccountEntity?>(null) }
    var resetErrorMessage by remember { mutableStateOf<String?>(null) }
    var isResetLoading by remember { mutableStateOf(false) }

    // State for Two-Factor Authentication (2FA) Challenge Modal
    val twoFactorPendingUser by viewModel.twoFactorPendingUser.collectAsState()
    var twoFactorCodeInput by remember { mutableStateOf("") }
    var trustThisDevice2Fa by remember { mutableStateOf(true) }
    var twoFactorError by remember { mutableStateOf<String?>(null) }
    var isVerifying2Fa by remember { mutableStateOf(false) }

    LaunchedEffect(twoFactorPendingUser) {
        if (twoFactorPendingUser != null) {
            isCredentialsSubmitting = false
            twoFactorCodeInput = ""
            twoFactorError = null
        }
    }

    // State for New Account Request Form
    var reqFullName by remember { mutableStateOf("") }
    var reqEmailOrPhone by remember { mutableStateOf("") }
    var reqUsername by remember { mutableStateOf("") }
    var reqPassword by remember { mutableStateOf("") }
    var reqPasswordVisible by remember { mutableStateOf(false) }
    val branchOptions = listOf(
        "فرع السودان (SD-001)",
        "فرع مصر والمصنع (EG-001)",
        "مخزن حلفا (HF-001)",
        "مخزن عطبرة (AT-002)"
    )
    var reqBranch by remember { mutableStateOf(branchOptions[0]) }

    val pendingCodeFromVm by viewModel.pendingApprovalUserCode.collectAsState()
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val isDark = PmpThemeController.isDarkModeActive

    val submitCredentialsLogin = {
        focusManager.clearFocus()
        errorMessage = null
        pendingApprovalBanner = null
        val resolvedIdentity = usernameInput.trim().ifBlank { emailInput.trim() }
        isCredentialsSubmitting = true
        viewModel.loginWithCredentials(
            emailOrUsername = resolvedIdentity,
            passwordOrPin = password,
            onSuccess = {
                isCredentialsSubmitting = false
                onLoginSuccess()
            },
            onError = { err ->
                isCredentialsSubmitting = false
                errorMessage = err
            }
        )
    }

    val liquidGlassPanelBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF191923).copy(alpha = 0.80f),
                    Color(0xFF111118).copy(alpha = 0.74f),
                    Color(0xFF15151E).copy(alpha = 0.80f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.78f),
                    Color(0xFFF8FAFF).copy(alpha = 0.66f),
                    Color.White.copy(alpha = 0.74f)
                )
            )
        }
    }

    val liquidGlassBorderBrush = remember(isDark) {
        if (isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.26f),
                    Color.White.copy(alpha = 0.08f),
                    Color(0xFF8B5CF6).copy(alpha = 0.24f)
                )
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.96f),
                    Color.White.copy(alpha = 0.45f),
                    Color(0xFFDDD6FE).copy(alpha = 0.75f)
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (isDark) {
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF05050A), Color(0xFF0B0B14), Color(0xFF06060B))
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFEEF2FF), Color(0xFFF5F3FF), Color(0xFFE0F2FE))
                    )
                }
            )
    ) {
        // Diffused iOS Liquid Glass Mesh Aurora Backdrop (Blurred on API 31+)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(48.dp) else Modifier)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF8B5CF6).copy(alpha = if (isDark) 0.26f else 0.24f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.18f, size.height * 0.16f),
                            radius = size.width * 0.65f
                        ),
                        radius = size.width * 0.65f,
                        center = Offset(size.width * 0.18f, size.height * 0.16f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                PmpBrandRed.copy(alpha = if (isDark) 0.22f else 0.16f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.88f, size.height * 0.22f),
                            radius = size.width * 0.55f
                        ),
                        radius = size.width * 0.55f,
                        center = Offset(size.width * 0.88f, size.height * 0.22f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF38BDF8).copy(alpha = if (isDark) 0.18f else 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.20f, size.height * 0.78f),
                            radius = size.width * 0.62f
                        ),
                        radius = size.width * 0.62f,
                        center = Offset(size.width * 0.20f, size.height * 0.78f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFA855F7).copy(alpha = if (isDark) 0.18f else 0.16f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.84f, size.height * 0.84f),
                            radius = size.width * 0.58f
                        ),
                        radius = size.width * 0.58f,
                        center = Offset(size.width * 0.84f, size.height * 0.84f)
                    )
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // --- Floating iOS Liquid Glass Brand Crest ---
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .padding(bottom = 18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .shadow(
                            elevation = 18.dp,
                            shape = RoundedCornerShape(26.dp),
                            ambientColor = Color(0xFF5B21B6).copy(alpha = 0.22f),
                            spotColor = PmpBrandRed.copy(alpha = 0.18f)
                        )
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            if (isDark) Color(0xFF1A1A24).copy(alpha = 0.88f)
                            else Color.White.copy(alpha = 0.88f)
                        )
                        .border(
                            BorderStroke(
                                1.5.dp,
                                Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.95f),
                                        Color(0xFFC4B5FD).copy(alpha = 0.60f),
                                        PmpBrandRed.copy(alpha = 0.35f)
                                    )
                                )
                            ),
                            RoundedCornerShape(26.dp)
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_pmp_emblem),
                        contentDescription = "شعار PMP Paints",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "PMP PAINTS",
                    color = if (isDark) Color.White else Color(0xFF0F172A),
                    fontWeight = FontWeight.Black,
                    fontSize = 23.sp,
                    letterSpacing = 0.6.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                AnimatedContent(
                    targetState = selectedAuthTab,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(150)) },
                    label = "HeroSubtitleTransition"
                ) { tab ->
                    Text(
                        text = if (tab == 0) "تسجيل الدخول إلى المنظومة" else "طلب حساب موظف جديد",
                        color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // --- Main iOS Liquid Glass Auth Panel ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(30.dp),
                        ambientColor = Color(0xFF312E81).copy(alpha = if (isDark) 0.45f else 0.14f),
                        spotColor = Color(0xFF6D28D9).copy(alpha = if (isDark) 0.35f else 0.12f)
                    )
                    .clip(RoundedCornerShape(30.dp))
                    .background(liquidGlassPanelBrush)
                    .border(BorderStroke(1.2.dp, liquidGlassBorderBrush), RoundedCornerShape(30.dp))
            ) {
                // Specular top-rim liquid highlight
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (isDark) 0.35f else 0.95f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    // iOS Animated Sliding Segmented Control
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.08f)
                                else Color(0xFFE2E8F0).copy(alpha = 0.60f)
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = if (isDark) 0.12f else 0.70f),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(3.5.dp)
                    ) {
                        val segmentWidth = (maxWidth / 2f).coerceAtLeast(100.dp)
                        val indicatorOffset by animateDpAsState(
                            targetValue = if (selectedAuthTab == 0) 0.dp else segmentWidth,
                            animationSpec = spring(
                                dampingRatio = 0.78f,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "SegmentThumbOffset"
                        )

                        // Sliding Frosted Active Thumb
                        Box(
                            modifier = Modifier
                                .offset(x = indicatorOffset)
                                .width(segmentWidth)
                                .fillMaxHeight()
                                .shadow(
                                    elevation = 4.dp,
                                    shape = RoundedCornerShape(13.dp),
                                    ambientColor = Color.Black.copy(alpha = 0.10f)
                                )
                                .clip(RoundedCornerShape(13.dp))
                                .background(
                                    if (isDark) Color(0xFF272734).copy(alpha = 0.95f)
                                    else Color.White.copy(alpha = 0.95f)
                                )
                                .border(
                                    0.8.dp,
                                    Color.White.copy(alpha = if (isDark) 0.18f else 0.95f),
                                    RoundedCornerShape(13.dp)
                                )
                        )

                        Row(modifier = Modifier.fillMaxSize()) {
                            listOf("تسجيل الدخول", "طلب حساب جديد").forEachIndexed { idx, label ->
                                val isSelected = selectedAuthTab == idx
                                val textColor by animateColorAsState(
                                    targetValue = if (isSelected) {
                                        if (idx == 0) PmpBrandRed else Color(0xFF6D28D9)
                                    } else {
                                        if (isDark) Color(0xFFA1A1AA) else Color(0xFF475569)
                                    },
                                    animationSpec = tween(200),
                                    label = "SegmentTextColor_$idx"
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(13.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (selectedAuthTab != idx) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                selectedAuthTab = idx
                                                errorMessage = null
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = textColor,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = selectedAuthTab,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(150))
                        },
                        label = "AuthTabContent"
                    ) { activeTab ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (activeTab == 0) {
                                // --- TAB 0: LOGIN ---
                                val inputUnfocusedBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.62f)
                                val inputFocusedBg = if (isDark) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.92f)
                                val inputBorderColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.85f)

                                // Unified Email or Username Input Field
                                Column {
                                    Text(
                                        text = "البريد الإلكتروني أو اسم المستخدم",
                                        color = TextDark,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                                    )
                                    OutlinedTextField(
                                        value = emailInput,
                                        onValueChange = {
                                            emailInput = it
                                            usernameInput = it
                                            errorMessage = null
                                            pendingApprovalBanner = null
                                        },
                                        placeholder = {
                                            Text(
                                                text = "اسم المستخدم أو البريد الإلكتروني",
                                                color = TextSubtle,
                                                fontSize = 12.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF6D28D9).copy(alpha = 0.10f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PersonOutline,
                                                    contentDescription = "اسم المستخدم أو البريد الإلكتروني",
                                                    tint = if (isDark) Color(0xFFC4B5FD) else Color(0xFF4C1D95),
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            if (emailInput.isNotEmpty()) {
                                                IconButton(onClick = {
                                                    emailInput = ""
                                                    usernameInput = ""
                                                }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Clear,
                                                        contentDescription = "مسح الحقل",
                                                        tint = TextSubtle,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = inputFocusedBg,
                                            unfocusedContainerColor = inputUnfocusedBg,
                                            focusedBorderColor = Color(0xFF7C3AED),
                                            unfocusedBorderColor = inputBorderColor,
                                            focusedTextColor = TextDark,
                                            unfocusedTextColor = TextDark,
                                            cursorColor = Color(0xFF7C3AED)
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Email,
                                            imeAction = ImeAction.Next
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("login_email_input")
                                    )
                                }

                                // Password Input Field
                                Column {
                                    Text(
                                        text = "كلمة المرور",
                                        color = TextDark,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                                    )
                                    OutlinedTextField(
                                        value = password,
                                        onValueChange = {
                                            password = it
                                            errorMessage = null
                                            pendingApprovalBanner = null
                                        },
                                        placeholder = {
                                            Text(
                                                text = "أدخل كلمة المرور",
                                                color = TextSubtle,
                                                fontSize = 12.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(PmpBrandRed.copy(alpha = 0.10f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = "كلمة المرور",
                                                    tint = PmpBrandRed,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { passwordVisible = !passwordVisible },
                                                modifier = Modifier.testTag("toggle_password_visibility")
                                            ) {
                                                Icon(
                                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                                    tint = TextMuted
                                                )
                                            }
                                        },
                                        visualTransformation = if (passwordVisible) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = inputFocusedBg,
                                            unfocusedContainerColor = inputUnfocusedBg,
                                            focusedBorderColor = PmpBrandRed,
                                            unfocusedBorderColor = inputBorderColor,
                                            focusedTextColor = TextDark,
                                            unfocusedTextColor = TextDark,
                                            cursorColor = PmpBrandRed
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                if (!isCredentialsSubmitting) submitCredentialsLogin()
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("login_password_input")
                                    )

                                    // Forgot Password Link
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            text = "نسيت كلمة المرور؟",
                                            color = if (isDark) Color(0xFFC4B5FD) else Color(0xFF5B21B6),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    resetIdentityInput = usernameInput.ifBlank { emailInput }
                                                    resetStep = 1
                                                    resetErrorMessage = null
                                                    enteredResetCode = ""
                                                    newPasswordInput = ""
                                                    showForgotPasswordDialog = true
                                                }
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                                .testTag("forgot_password_button")
                                        )
                                    }
                                }

                                // Pending Manager Approval Notice & Direct Check Button
                                AnimatedVisibility(visible = pendingApprovalBanner != null || pendingCodeFromVm != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color(0xFFFFFBEB).copy(alpha = 0.88f))
                                            .border(1.2.dp, Color(0xFFD97706).copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                            .padding(14.dp)
                                            .testTag("pending_approval_banner")
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.HourglassTop,
                                                    contentDescription = null,
                                                    tint = Color(0xFFB45309),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Text(
                                                        text = "بانتظار موافقة حساب المدير وتحديد الصلاحيات",
                                                        color = Color(0xFF92400E),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    if (pendingCodeFromVm != null) {
                                                        Text(
                                                            text = "مرجع الطلب: $pendingCodeFromVm",
                                                            color = Color(0xFFB45309),
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Text(
                                                        text = pendingApprovalBanner ?: "تم إرسال طلبك بنجاح وبانتظار اعتماد الإدارة.",
                                                        color = Color(0xFFB45309),
                                                        fontSize = 11.5.sp,
                                                        lineHeight = 17.sp
                                                    )
                                                }
                                            }

                                            GlassButton(
                                                text = if (isCheckingApproval) "جاري التحقق..." else "فحص الموافقة والدخول الآن",
                                                icon = Icons.Default.CloudSync,
                                                gradient = PmpBrandRedGradient,
                                                enabled = !isCheckingApproval,
                                                onClick = {
                                                    val resolvedIdentity = pendingCodeFromVm
                                                        ?: usernameInput.trim().ifBlank { emailInput.trim() }
                                                    if (resolvedIdentity.isBlank()) {
                                                        errorMessage = "يرجى كتابة اسم المستخدم لفحص الموافقة"
                                                        return@GlassButton
                                                    }
                                                    isCheckingApproval = true
                                                    errorMessage = null
                                                    viewModel.checkPendingAccountApprovalAndLogin(
                                                        userCodeOrUsername = resolvedIdentity,
                                                        passwordOrPin = password,
                                                        onSuccess = {
                                                            isCheckingApproval = false
                                                            onLoginSuccess()
                                                        },
                                                        onStillPending = { stillPendingMsg ->
                                                            isCheckingApproval = false
                                                            pendingApprovalBanner = stillPendingMsg
                                                        },
                                                        onError = { err ->
                                                            isCheckingApproval = false
                                                            errorMessage = err
                                                        }
                                                    )
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(44.dp)
                                                    .testTag("check_approval_login_btn")
                                            )
                                        }
                                    }
                                }

                                // Error Message Banner
                                AnimatedVisibility(visible = errorMessage != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(PmpRed.copy(alpha = 0.10f))
                                            .border(1.dp, PmpRed.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                tint = PmpRed,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = errorMessage.orEmpty(),
                                                color = PmpRed,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                // Primary iOS-style Liquid Sign-In Pill Button
                                val loginBtnInteraction = remember { MutableInteractionSource() }
                                val isLoginBtnPressed by loginBtnInteraction.collectIsPressedAsState()
                                val loginBtnScale by animateFloatAsState(
                                    targetValue = if (isLoginBtnPressed) 0.97f else 1f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                    label = "LoginButtonScale"
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .scale(loginBtnScale)
                                        .shadow(
                                            elevation = 10.dp,
                                            shape = RoundedCornerShape(18.dp),
                                            ambientColor = PmpBrandRed.copy(alpha = 0.30f),
                                            spotColor = PmpBrandRed.copy(alpha = 0.28f)
                                        )
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(PmpBrandRedGradient)
                                        .border(
                                            1.dp,
                                            Color.White.copy(alpha = 0.45f),
                                            RoundedCornerShape(18.dp)
                                        )
                                        .clickable(
                                            interactionSource = loginBtnInteraction,
                                            indication = LocalIndicationFallback(),
                                            enabled = !isCredentialsSubmitting
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            submitCredentialsLogin()
                                        }
                                        .testTag("login_submit_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        if (isCredentialsSubmitting) {
                                            CircularProgressIndicator(
                                                color = Color.White,
                                                strokeWidth = 2.2.dp,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "جاري التحقق...",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Login,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(19.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "تسجيل الدخول",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.5.sp
                                            )
                                        }
                                    }
                                }

                                // Clean Quick Auth Divider
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFCBD5E1).copy(alpha = 0.70f)
                                    )
                                    Text(
                                        text = "أو الدخول السريع",
                                        color = TextSubtle,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFCBD5E1).copy(alpha = 0.70f)
                                    )
                                }

                                // Symmetrical Circular Quick Sign-In Icons (Fingerprint Icon-Only Circle + Google Circle)
                                val localContext = LocalContext.current
                                val fragmentActivity = localContext as? androidx.fragment.app.FragmentActivity
                                val bioInteraction = remember { MutableInteractionSource() }
                                val isBioPressed by bioInteraction.collectIsPressedAsState()
                                val bioScale by animateFloatAsState(
                                    targetValue = if (isBioPressed) 0.91f else 1f,
                                    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
                                    label = "BiometricCircleScale"
                                )
                                var isGoogleSigningIn by remember { mutableStateOf(false) }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Circular Icon-Only Biometric Fingerprint Button (No account/code text exposed)
                                    Surface(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val boundUser = com.example.util.BiometricPreferenceManager.getBoundUser(localContext)
                                            if (boundUser == null) {
                                                errorMessage = "يرجى تسجيل الدخول بكلمة المرور أولاً لتفعيل البصمة على هذا الجهاز."
                                                return@Surface
                                            }
                                            if (fragmentActivity != null) {
                                                errorMessage = null
                                                BiometricAuthHelper.authenticate(
                                                    activity = fragmentActivity,
                                                    title = "تسجيل الدخول بالبصمة",
                                                    subtitle = "التحقق من الهوية البيومترية",
                                                    description = "ضع إصبعك على المستشعر للمتابعة",
                                                    onSuccess = {
                                                        viewModel.loginWithBiometrics(
                                                            onSuccess = { onLoginSuccess() },
                                                            onError = { err -> errorMessage = err }
                                                        )
                                                    },
                                                    onError = { err -> errorMessage = err }
                                                )
                                            } else {
                                                errorMessage = "ميزة البصمة غير متوفرة في بيئة الجهاز الحالية"
                                            }
                                        },
                                        shape = CircleShape,
                                        color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.86f),
                                        border = BorderStroke(
                                            1.5.dp,
                                            Brush.linearGradient(
                                                listOf(
                                                    Color(0xFF7C3AED).copy(alpha = 0.55f),
                                                    Color.White.copy(alpha = 0.90f),
                                                    Color(0xFF6D28D9).copy(alpha = 0.45f)
                                                )
                                            )
                                        ),
                                        shadowElevation = 8.dp,
                                        interactionSource = bioInteraction,
                                        modifier = Modifier
                                            .size(60.dp)
                                            .scale(bioScale)
                                            .testTag("biometric_login_button")
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Fingerprint,
                                                contentDescription = "تسجيل الدخول بالبصمة",
                                                tint = if (isDark) Color(0xFFC4B5FD) else Color(0xFF6D28D9),
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }
                                    }

                                    // Circular Google Identity Button
                                    GoogleIdentityLogoButton(
                                        onAuthSuccess = {
                                            if (isGoogleSigningIn) return@GoogleIdentityLogoButton
                                            isGoogleSigningIn = true
                                            val fbUser = Firebase.auth.currentUser
                                            if (fbUser != null) {
                                                viewModel.bindFirebaseGoogleUser(
                                                    uid = fbUser.uid,
                                                    email = fbUser.email,
                                                    displayName = fbUser.displayName,
                                                    onApprovedSuccess = {
                                                        isGoogleSigningIn = false
                                                        onLoginSuccess()
                                                    },
                                                    onPendingApproval = { pendingMsg ->
                                                        isGoogleSigningIn = false
                                                        pendingApprovalBanner = pendingMsg
                                                    }
                                                )
                                            } else {
                                                isGoogleSigningIn = false
                                            }
                                        },
                                        onAuthError = { errMsg ->
                                            isGoogleSigningIn = false
                                            errorMessage = errMsg
                                        }
                                    )
                                }

                            } else {
                                // --- TAB 1: REQUEST NEW EMPLOYEE ACCOUNT ---

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isDark) Color(0xFF1E1B4B).copy(alpha = 0.45f) else Color(0xFFEFF6FF).copy(alpha = 0.85f))
                                        .border(
                                            1.dp,
                                            if (isDark) Color(0xFF6366F1).copy(alpha = 0.35f) else Color(0xFFBFDBFE),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = if (isDark) Color(0xFFA5B4FC) else PmpBrandNavy,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "سيتم إرسال طلبك إلى الإدارة لمراجعته واعتماد الصلاحيات المناسبة لحسابك.",
                                            color = if (isDark) Color(0xFFE0E7FF) else PmpBrandNavy,
                                            fontSize = 11.5.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }

                        // Full Name
                        Column {
                            Text(
                                text = "الاسم الكامل للموظف",
                                color = TextDark,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = reqFullName,
                                onValueChange = {
                                    reqFullName = it
                                    errorMessage = null
                                },
                                placeholder = {
                                    Text(
                                        text = "مثال: أحمد عبد الله - مبيعات",
                                        color = TextSubtle,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Badge,
                                        contentDescription = null,
                                        tint = PmpBrandNavy
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                                    unfocusedContainerColor = GlassInput,
                                    focusedBorderColor = PmpBrandNavy,
                                    unfocusedBorderColor = GlassBorder,
                                    focusedTextColor = TextDark,
                                    unfocusedTextColor = TextDark
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("req_fullname_input")
                            )
                        }

                        // Email or Phone
                        Column {
                            Text(
                                text = "البريد الإلكتروني أو رقم الهاتف",
                                color = TextDark,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = reqEmailOrPhone,
                                onValueChange = {
                                    reqEmailOrPhone = it
                                    errorMessage = null
                                },
                                placeholder = {
                                    Text(
                                        text = "ahmed@pmp-paints.com أو 0912345678",
                                        color = TextSubtle,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContactMail,
                                        contentDescription = null,
                                        tint = PmpBrandNavy
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                                    unfocusedContainerColor = GlassInput,
                                    focusedBorderColor = PmpBrandNavy,
                                    unfocusedBorderColor = GlassBorder,
                                    focusedTextColor = TextDark,
                                    unfocusedTextColor = TextDark
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("req_email_phone_input")
                            )
                        }

                        // Proposed Username
                        Column {
                            Text(
                                text = "اسم المستخدم المقترح (Username)",
                                color = TextDark,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = reqUsername,
                                onValueChange = {
                                    reqUsername = it
                                    errorMessage = null
                                },
                                placeholder = {
                                    Text(
                                        text = "مثال: ahmed_sales",
                                        color = TextSubtle,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AlternateEmail,
                                        contentDescription = null,
                                        tint = PmpBrandNavy
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                                    unfocusedContainerColor = GlassInput,
                                    focusedBorderColor = PmpBrandNavy,
                                    unfocusedBorderColor = GlassBorder,
                                    focusedTextColor = TextDark,
                                    unfocusedTextColor = TextDark
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("req_username_input")
                            )
                        }

                        // Password / PIN
                        Column {
                            Text(
                                text = "كلمة المرور / رمز الدخول",
                                color = TextDark,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            OutlinedTextField(
                                value = reqPassword,
                                onValueChange = {
                                    reqPassword = it
                                    errorMessage = null
                                },
                                placeholder = {
                                    Text(
                                        text = "أدخل كلمة مرور لا تقل عن 4 خانات",
                                        color = TextSubtle,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = PmpBrandRed
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { reqPasswordVisible = !reqPasswordVisible }) {
                                        Icon(
                                            imageVector = if (reqPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint = TextMuted
                                        )
                                    }
                                },
                                visualTransformation = if (reqPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                                    unfocusedContainerColor = GlassInput,
                                    focusedBorderColor = PmpBrandRed,
                                    unfocusedBorderColor = GlassBorder,
                                    focusedTextColor = TextDark,
                                    unfocusedTextColor = TextDark
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("req_password_input")
                            )
                        }

                        // Branch Dropdown
                        GlassDropdown(
                            label = "فرع العمل المطلوب",
                            options = branchOptions,
                            selectedOption = reqBranch,
                            onOptionSelected = { reqBranch = it }
                        )

                        // Error Message Banner
                        AnimatedVisibility(visible = errorMessage != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(PmpRed.copy(alpha = 0.10f))
                                    .border(1.dp, PmpRed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = PmpRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = errorMessage.orEmpty(),
                                        color = PmpRed,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Submit Request Button
                        GlassButton(
                            text = if (isCredentialsSubmitting) "جاري إرسال الطلب..." else "إرسال طلب الانضمام",
                            icon = Icons.AutoMirrored.Filled.Send,
                            gradient = PmpBrandRedGradient,
                            enabled = !isCredentialsSubmitting,
                            onClick = {
                                isCredentialsSubmitting = true
                                errorMessage = null
                                viewModel.submitNewEmployeeRequest(
                                    fullName = reqFullName,
                                    emailOrPhone = reqEmailOrPhone,
                                    username = reqUsername,
                                    passwordOrPin = reqPassword,
                                    branch = reqBranch,
                                    onSuccess = { requestedUser ->
                                        isCredentialsSubmitting = false
                                        usernameInput = requestedUser.username
                                        password = reqPassword
                                        pendingApprovalBanner = "تم إرسال طلبك بنجاح وهو الآن قيد المراجعة."
                                        selectedAuthTab = 0
                                    },
                                    onError = { err ->
                                        isCredentialsSubmitting = false
                                        errorMessage = err
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("submit_employee_request_btn")
                        )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Modal: Two-Factor Authentication (2FA) Challenge ---
    val pending2FaUser = twoFactorPendingUser
    if (pending2FaUser != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isVerifying2Fa) viewModel.cancelTwoFactorLogin()
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = PmpGold)
                    Text(
                        text = "المصادقة الثنائية (2FA)",
                        color = PmpCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "الحساب (${pending2FaUser.fullName} • ${pending2FaUser.userCode}) محمي بالمصادقة الثنائية. أدخل رمز التحقق المكون من 6 أرقام من تطبيق المصادقة أو أحد أكواد الاسترداد الاحتياطية (8 خانات):",
                        color = TextWhite,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                    GlassTextField(
                        value = twoFactorCodeInput,
                        onValueChange = {
                            twoFactorCodeInput = it
                            twoFactorError = null
                        },
                        label = "رمز 2FA (6 أرقام) أو كود الاسترداد الاحتياطي *"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { trustThisDevice2Fa = !trustThisDevice2Fa }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = trustThisDevice2Fa,
                            onCheckedChange = { trustThisDevice2Fa = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "توثيق هذا الجهاز كجهاز موثوق لهذا الحساب",
                            color = TextWhite,
                            fontSize = 12.sp
                        )
                    }
                    if (twoFactorError != null) {
                        Text(
                            text = twoFactorError.orEmpty(),
                            color = PmpRed,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    text = if (isVerifying2Fa) "جاري التحقق..." else "تأكيد الدخول",
                    enabled = !isVerifying2Fa,
                    onClick = {
                        if (twoFactorCodeInput.trim().length < 6) {
                            twoFactorError = "يرجى إدخال رمز التحقق المكون من 6 أرقام أو كود الاسترداد"
                            return@GlassButton
                        }
                        isVerifying2Fa = true
                        twoFactorError = null
                        viewModel.verifyTwoFactorLogin(
                            codeInput = twoFactorCodeInput,
                            trustCurrentDevice = trustThisDevice2Fa,
                            onSuccess = {
                                isVerifying2Fa = false
                                onLoginSuccess()
                            },
                            onError = { err ->
                                isVerifying2Fa = false
                                twoFactorError = err
                            }
                        )
                    }
                )
            },
            dismissButton = {
                GlassButton(
                    text = "إلغاء",
                    onClick = { viewModel.cancelTwoFactorLogin() }
                )
            },
            containerColor = PmpNavySurface
        )
    }

    // --- Modal: Forgot Password, Attempt-Limited OTP & Backup Code Account Recovery ---
    if (showForgotPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isResetLoading) showForgotPasswordDialog = false
            },
            title = {
                Text(
                    text = if (recoveryMode == 1) {
                        "استرداد الحساب بكود احتياطي"
                    } else if (resetStep == 1) {
                        "استعادة وتعيين كلمة المرور"
                    } else {
                        "التحقق وتعيين كلمة المرور الجديدة"
                    },
                    color = PmpCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Recovery mode selector tabs (OTP vs Backup Recovery Code)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    recoveryMode = 0
                                    resetErrorMessage = null
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (recoveryMode == 0) PmpCyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, if (recoveryMode == 0) PmpCyan else GlassBorder)
                        ) {
                            Text(
                                text = "رمز تحقق مؤقت (OTP)",
                                color = TextWhite,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    recoveryMode = 1
                                    resetErrorMessage = null
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (recoveryMode == 1) PmpGold.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, if (recoveryMode == 1) PmpGold else GlassBorder)
                        ) {
                            Text(
                                text = "كود استرداد احتياطي",
                                color = TextWhite,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
                            )
                        }
                    }

                    if (recoveryMode == 1) {
                        Text(
                            text = "استخدم أحد أكواد الاسترداد الاحتياطية (8 خانات) التي قمت بحفظها عند تفعيل الأمان المتقدم لفك قفل الحساب وتعيين كلمة مرور جديدة فوراً:",
                            color = TextWhite,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        GlassTextField(
                            value = resetIdentityInput,
                            onValueChange = {
                                resetIdentityInput = it
                                resetErrorMessage = null
                            },
                            label = "اسم المستخدم أو البريد الإلكتروني أو كود الحساب *"
                        )
                        GlassTextField(
                            value = backupRecoveryCodeInput,
                            onValueChange = {
                                backupRecoveryCodeInput = it
                                resetErrorMessage = null
                            },
                            label = "كود الاسترداد الاحتياطي (مثال: ABCD-EFGH) *"
                        )
                        GlassTextField(
                            value = newPasswordInput,
                            onValueChange = {
                                newPasswordInput = it
                                resetErrorMessage = null
                            },
                            label = "كلمة المرور الجديدة (4 خانات على الأقل) *"
                        )
                    } else if (resetStep == 1) {
                        Text(
                            text = "أدخل اسم المستخدم أو البريد الإلكتروني المرتبط بحسابك، ثم اختر وسيلة استلام رمز التحقق المؤقت (صالح لمدة 10 دقائق وبحد أقصى 3 محاولات):",
                            color = TextWhite,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp
                        )

                        GlassTextField(
                            value = resetIdentityInput,
                            onValueChange = {
                                resetIdentityInput = it
                                resetErrorMessage = null
                            },
                            label = "اسم المستخدم أو البريد الإلكتروني *"
                        )

                        Text(
                            text = "وسيلة إرسال رمز التحقق:",
                            color = PmpGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Option 1: Google Email
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (resetChannelChoice == "EMAIL") PmpCyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (resetChannelChoice == "EMAIL") PmpCyan else GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { resetChannelChoice = "EMAIL" }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = resetChannelChoice == "EMAIL",
                                onClick = { resetChannelChoice = "EMAIL" },
                                colors = RadioButtonDefaults.colors(selectedColor = PmpCyan)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "إرسال رمز التحقق إلى بريد Google الخاص بي",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "يصلك كود التحقق المكون من 6 أرقام إلى بريدك الإلكتروني المسجل",
                                    color = TextMuted,
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        // Option 2: Manager Notification Screen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (resetChannelChoice == "MANAGER_ALERT") PmpGold.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (resetChannelChoice == "MANAGER_ALERT") PmpGold else GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { resetChannelChoice = "MANAGER_ALERT" }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = resetChannelChoice == "MANAGER_ALERT",
                                onClick = { resetChannelChoice = "MANAGER_ALERT" },
                                colors = RadioButtonDefaults.colors(selectedColor = PmpGold)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "إرسال إشعار ورمز إلى شاشة إشعارات المدير",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "يظهر إشعار فوري للمدير على السحابة يوضح طلب الموظف وكود الاستعادة",
                                    color = TextMuted,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    } else {
                        // Step 2: Enter OTP Code + New Password
                        val channelNotice = if (resetChannelChoice == "EMAIL") {
                            "تم إنشاء كود التحقق المؤقت (صالح لمدة 10 دقائق) لبريد Google المسجل (${targetResetUser?.phoneOrEmail?.ifBlank { "لحسابك" }}). أدخل الكود أدناه:"
                        } else {
                            "تم إرسال إشعار رسمي برمز التحقق المؤقت (صالح لمدة 10 دقائق) إلى شاشة إشعارات المدير المركزي. احصل على الكود من المدير وأدخله أدناه:"
                        }
                        Text(
                            text = channelNotice,
                            color = TextWhite,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp
                        )

                        GlassTextField(
                            value = enteredResetCode,
                            onValueChange = {
                                enteredResetCode = it
                                resetErrorMessage = null
                            },
                            label = "كود التحقق (6 أرقام - صالح 10 دقائق) *",
                            isNumber = true
                        )

                        GlassTextField(
                            value = newPasswordInput,
                            onValueChange = {
                                newPasswordInput = it
                                resetErrorMessage = null
                            },
                            label = "كلمة المرور الجديدة (4 أحرف أو أرقام على الأقل) *"
                        )
                    }

                    if (resetErrorMessage != null) {
                        Text(
                            text = resetErrorMessage.orEmpty(),
                            color = PmpRed,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                if (recoveryMode == 1) {
                    GlassButton(
                        text = if (isResetLoading) "جاري استرداد الحساب..." else "استرداد الحساب وتحديث كلمة المرور",
                        enabled = !isResetLoading,
                        onClick = {
                            val targetId = resetIdentityInput.trim()
                            val recoveryClean = backupRecoveryCodeInput.trim()
                            val passClean = newPasswordInput.trim()
                            if (targetId.isBlank()) {
                                resetErrorMessage = "يرجى إدخال اسم المستخدم أو البريد الإلكتروني أو كود الحساب"
                                return@GlassButton
                            }
                            if (recoveryClean.length < 6) {
                                resetErrorMessage = "يرجى إدخال كود الاسترداد الاحتياطي الصحيح"
                                return@GlassButton
                            }
                            if (passClean.length < 4) {
                                resetErrorMessage = "كلمة المرور الجديدة يجب أن تكون 4 خانات على الأقل"
                                return@GlassButton
                            }
                            isResetLoading = true
                            resetErrorMessage = null
                            viewModel.recoverAccountWithBackupCode(
                                identity = targetId,
                                backupRecoveryCode = recoveryClean,
                                newPass = passClean,
                                onSuccess = { updatedUser ->
                                    isResetLoading = false
                                    showForgotPasswordDialog = false
                                    password = passClean
                                    usernameInput = updatedUser.username
                                },
                                onError = { err ->
                                    isResetLoading = false
                                    resetErrorMessage = err
                                }
                            )
                        }
                    )
                } else if (resetStep == 1) {
                    GlassButton(
                        text = if (isResetLoading) "جاري البحث والإرسال..." else "إرسال رمز التحقق",
                        enabled = !isResetLoading,
                        onClick = {
                            val targetId = resetIdentityInput.trim()
                            if (targetId.isBlank()) {
                                resetErrorMessage = "يرجى كتابة اسم المستخدم أو البريد الإلكتروني"
                                return@GlassButton
                            }
                            isResetLoading = true
                            resetErrorMessage = null
                            viewModel.requestPasswordReset(
                                identity = targetId,
                                channel = resetChannelChoice,
                                onCodeReady = { code, user ->
                                    isResetLoading = false
                                    generatedResetCode = code
                                    targetResetUser = user
                                    resetStep = 2
                                },
                                onError = { err ->
                                    isResetLoading = false
                                    resetErrorMessage = err
                                }
                            )
                        }
                    )
                } else {
                    GlassButton(
                        text = if (isResetLoading) "جاري تعيين كلمة المرور..." else "تأكيد وحفظ كلمة المرور الجديدة",
                        enabled = !isResetLoading,
                        onClick = {
                            val codeClean = enteredResetCode.trim()
                            val passClean = newPasswordInput.trim()
                            if (codeClean.length != 6) {
                                resetErrorMessage = "يرجى إدخال كود التحقق المكون من 6 أرقام"
                                return@GlassButton
                            }
                            if (passClean.length < 4) {
                                resetErrorMessage = "كلمة المرور الجديدة يجب أن تكون 4 خانات على الأقل"
                                return@GlassButton
                            }
                            val user = targetResetUser ?: return@GlassButton
                            isResetLoading = true
                            resetErrorMessage = null
                            viewModel.confirmPasswordResetWithOtp(
                                userId = user.id,
                                otpInput = codeClean,
                                newPass = passClean,
                                onSuccess = { updatedUser ->
                                    isResetLoading = false
                                    showForgotPasswordDialog = false
                                    password = passClean
                                    usernameInput = updatedUser.username
                                    viewModel.postMessage("تم تغيير كلمة المرور بنجاح للحساب (${updatedUser.fullName})")
                                },
                                onError = { err ->
                                    isResetLoading = false
                                    resetErrorMessage = err
                                }
                            )
                        }
                    )
                }
            },
            dismissButton = {
                GlassButton(
                    text = "إلغاء",
                    onClick = { showForgotPasswordDialog = false }
                )
            },
            containerColor = PmpNavySurface
        )
    }
}

@Composable
private fun LocalIndicationFallback() = ripple()

@Composable
fun GoogleIdentityLogoButton(
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "GoogleCircleScale"
    )
    val isDark = PmpThemeController.isDarkModeActive
    var isLoading by remember { mutableStateOf(false) }

    Surface(
        onClick = {
            if (!isLoading) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                isLoading = true
                onGoogleSignInClicked(
                    context = context,
                    credentialManager = credentialManager,
                    onAuthSuccess = {
                        isLoading = false
                        onAuthSuccess()
                    },
                    onAuthError = { errorMsg ->
                        isLoading = false
                        onAuthError(errorMsg)
                    },
                    scope = coroutineScope,
                    onAuthCancelled = {
                        isLoading = false
                    }
                )
            }
        },
        enabled = !isLoading,
        shape = CircleShape,
        color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.88f),
        border = BorderStroke(
            1.8.dp,
            Brush.sweepGradient(
                listOf(
                    Color(0xFF4285F4),
                    Color(0xFF34A853),
                    Color(0xFFFBBC05),
                    Color(0xFFEA4335),
                    Color(0xFF4285F4)
                )
            )
        ),
        shadowElevation = 8.dp,
        interactionSource = interactionSource,
        modifier = modifier
            .size(60.dp)
            .scale(pressScale)
            .testTag("google_sign_in_button")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color(0xFF4285F4),
                    strokeWidth = 2.5.dp
                )
            } else {
                Text(
                    text = "G",
                    color = if (isDark) Color(0xFF93C5FD) else Color(0xFF4285F4),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp
                )
            }
        }
    }
}
