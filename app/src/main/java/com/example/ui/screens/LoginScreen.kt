package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.foundation.clickable
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

    // State for Forgot Password Modal
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var resetIdentityInput by remember { mutableStateOf("") }
    var resetChannelChoice by remember { mutableStateOf("EMAIL") } // "EMAIL" or "MANAGER_ALERT"
    var resetStep by remember { mutableIntStateOf(1) } // 1 = request, 2 = verify and reset
    var generatedResetCode by remember { mutableStateOf("") }
    var enteredResetCode by remember { mutableStateOf("") }
    var newPasswordInput by remember { mutableStateOf("") }
    var targetResetUser by remember { mutableStateOf<UserAccountEntity?>(null) }
    var resetErrorMessage by remember { mutableStateOf<String?>(null) }
    var isResetLoading by remember { mutableStateOf(false) }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassBackgroundBrush)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            PmpBrandRed.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.14f),
                        radius = size.width * 0.55f
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.85f, size.height * 0.14f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            PmpBrandNavy.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.15f, size.height * 0.45f),
                        radius = size.width * 0.60f
                    ),
                    radius = size.width * 0.60f,
                    center = Offset(size.width * 0.15f, size.height * 0.45f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            PmpBrandOrange.copy(alpha = 0.09f),
                            PmpBrandGreen.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.80f, size.height * 0.85f),
                        radius = size.width * 0.55f
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.80f, size.height * 0.85f)
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // --- Brand Glass Header Card ---
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                cornerRadius = 26.dp,
                glowAccentColor = PmpBrandRed
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(PmpLogoSwooshGradient)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White)
                                .border(
                                    BorderStroke(1.5.dp, PmpLogoSwooshGradient),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_pmp_emblem),
                                contentDescription = "شعار PMP Paints",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "PMP PAINTS",
                            color = PmpBrandNavy,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 21.sp
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = if (selectedAuthTab == 0) "تسجيل الدخول إلى المنظومة" else "طلب حساب موظف جديد واعتماد الصلاحيات",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Main Glass-Morphism Auth Card ---
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                cornerRadius = 26.dp,
                glowAccentColor = PmpBrandNavy
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Top Tabs: تسجيل الدخول vs طلب حساب جديد
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF1F5F9))
                            .padding(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedAuthTab == 0) Color.White else Color.Transparent)
                                .clickable {
                                    selectedAuthTab = 0
                                    errorMessage = null
                                }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "تسجيل الدخول",
                                color = if (selectedAuthTab == 0) PmpBrandRed else TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = if (selectedAuthTab == 0) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedAuthTab == 1) Color.White else Color.Transparent)
                                .clickable {
                                    selectedAuthTab = 1
                                    errorMessage = null
                                }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "طلب حساب جديد",
                                color = if (selectedAuthTab == 1) PmpBrandNavy else TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = if (selectedAuthTab == 1) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    if (selectedAuthTab == 0) {
                        // --- TAB 0: LOGIN ---

                        // Unified Email or Username Input Field
                        Column {
                            Text(
                                text = "البريد الإلكتروني أو اسم المستخدم",
                                color = TextDark,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
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
                                        text = "اكتب البريد الإلكتروني أو اسم المستخدم...",
                                        color = TextSubtle,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "اسم المستخدم أو البريد الإلكتروني",
                                        tint = PmpBrandNavy
                                    )
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
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                                    unfocusedContainerColor = GlassInput,
                                    focusedBorderColor = PmpBrandNavy,
                                    unfocusedBorderColor = GlassBorder,
                                    focusedTextColor = TextDark,
                                    unfocusedTextColor = TextDark,
                                    cursorColor = PmpBrandNavy
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
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
                                modifier = Modifier.padding(bottom = 6.dp)
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
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "كلمة المرور",
                                        tint = PmpBrandRed
                                    )
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
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.95f),
                                    unfocusedContainerColor = GlassInput,
                                    focusedBorderColor = PmpBrandRed,
                                    unfocusedBorderColor = GlassBorder,
                                    focusedTextColor = TextDark,
                                    unfocusedTextColor = TextDark,
                                    cursorColor = PmpBrandRed
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("login_password_input")
                            )

                            // Forgot Password Link
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "نسيت كلمة المرور؟",
                                    color = PmpBrandNavy,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            resetIdentityInput = usernameInput.ifBlank { emailInput }
                                            resetStep = 1
                                            resetErrorMessage = null
                                            enteredResetCode = ""
                                            newPasswordInput = ""
                                            showForgotPasswordDialog = true
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .testTag("forgot_password_button")
                                )
                            }
                        }

                        // Pending Manager Approval Notice & Direct Check Button
                        AnimatedVisibility(visible = pendingApprovalBanner != null || pendingCodeFromVm != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFFFFFBEB))
                                    .border(1.2.dp, Color(0xFFD97706), RoundedCornerShape(14.dp))
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
                                                    text = "كود الحساب المطلوب: $pendingCodeFromVm",
                                                    color = Color(0xFFB45309),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Text(
                                                text = pendingApprovalBanner ?: "تم إرسال طلبك إلى سيرفر Google Cloud وسيظهر لدى حساب المدير لاعتماد الصلاحيات.",
                                                color = Color(0xFFB45309),
                                                fontSize = 11.5.sp,
                                                lineHeight = 17.sp
                                            )
                                        }
                                    }

                                    // Button to check manager approval and enter immediately
                                    GlassButton(
                                        text = if (isCheckingApproval) "جاري التحقق من موافقة المدير..." else "فحص موافقة المدير وتفعيل الدخول الآن",
                                        icon = Icons.Default.CloudSync,
                                        gradient = PmpBrandRedGradient,
                                        enabled = !isCheckingApproval,
                                        onClick = {
                                            val resolvedIdentity = pendingCodeFromVm
                                                ?: usernameInput.trim().ifBlank { emailInput.trim() }
                                            if (resolvedIdentity.isBlank()) {
                                                errorMessage = "يرجى كتابة اسم المستخدم أو كود الحساب لفحص الموافقة"
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

                        // Primary Sign-In Button
                        GlassButton(
                            text = if (isCredentialsSubmitting) "جاري التحقق من السيرفر..." else "تسجيل الدخول",
                            icon = Icons.Default.Login,
                            gradient = PmpBrandRedGradient,
                            enabled = !isCredentialsSubmitting,
                            onClick = {
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
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("login_submit_button")
                        )

                        // Quick Biometric Sign-In (Fingerprint / Face Unlock) Button
                        val localContext = LocalContext.current
                        val fragmentActivity = localContext as? androidx.fragment.app.FragmentActivity
                        var boundBiometricUser by remember { mutableStateOf(com.example.util.BiometricPreferenceManager.getBoundUser(localContext)) }

                        GlassButton(
                            text = if (boundBiometricUser != null) {
                                "تسجيل الدخول بالبصمة (${boundBiometricUser?.fullName})"
                            } else {
                                "تسجيل الدخول ببصمة الإصبع أو الوجه"
                            },
                            icon = Icons.Default.Fingerprint,
                            gradient = GlassAccentGoldGradient,
                            onClick = {
                                if (boundBiometricUser == null) {
                                    errorMessage = "لم يتم تفعيل البصمة لأي حساب على هذا الهاتف بعد. يرجى تسجيل الدخول باسم المستخدم وكلمة المرور أولاً لربط بصمتك بحسابك ومنع تداخل الحسابات."
                                    return@GlassButton
                                }
                                if (fragmentActivity != null) {
                                    errorMessage = null
                                    val boundName = boundBiometricUser?.fullName ?: "الموظف"
                                    BiometricAuthHelper.authenticate(
                                        activity = fragmentActivity,
                                        title = "تسجيل الدخول بالبصمة",
                                        subtitle = "التحقق من هوية: $boundName",
                                        description = "ضع إصبعك على المستشعر لتأكيد هويتك لحساب ($boundName)",
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("biometric_login_button")
                        )

                        if (boundBiometricUser != null) {
                            Text(
                                text = "البصمة مرتبطة بحساب: ${boundBiometricUser?.fullName} (${boundBiometricUser?.role}) • إلغاء الربط",
                                color = PmpBrandNavy,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable {
                                        com.example.util.BiometricPreferenceManager.clearBinding(localContext)
                                        boundBiometricUser = null
                                        errorMessage = null
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }

                        // Divider for Google Identity Logo Login
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = GlassBorder
                            )
                            Text(
                                text = "أو الدخول عبر هوية Google",
                                color = TextSubtle,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = GlassBorder
                            )
                        }

                        // Google Account Identity Logo Button
                        var isGoogleSigningIn by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFEFF6FF))
                                .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = PmpBrandNavy,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "عند إرسال الطلب، سيتم إرساله مباشرة لسيرفر Google Cloud المركزي ويظهر فوراً لهاتف مدير النظام لتحديد الصلاحيات والموافقة.",
                                    color = PmpBrandNavy,
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
                            text = if (isCredentialsSubmitting) "جاري إرسال الطلب للسيرفر..." else "إرسال طلب الانضمام إلى مدير النظام",
                            icon = Icons.Default.Send,
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
                                        pendingApprovalBanner = "تم إرسال طلبك بنجاح بكود (${requestedUser.userCode}) ووصل هاتف المدير لاعتماد الصلاحيات."
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

    // --- Modal: Forgot Password & Verification OTP ---
    if (showForgotPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isResetLoading) showForgotPasswordDialog = false
            },
            title = {
                Text(
                    text = if (resetStep == 1) "استعادة وتعيين كلمة المرور" else "التحقق وتعيين كلمة المرور الجديدة",
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
                    if (resetStep == 1) {
                        Text(
                            text = "أدخل اسم المستخدم أو البريد الإلكتروني المرتبط بحسابك، ثم اختر وسيلة استلام رمز التحقق (OTP):",
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
                            "تم إرسال كود التحقق (OTP) إلى بريد Google المسجل (${targetResetUser?.phoneOrEmail?.ifBlank { "لحسابك" }}). أدخل الكود أدناه:"
                        } else {
                            "تم إرسال إشعار رسمي برمز التحقق إلى شاشة إشعارات المدير المركزي على السحابة. احصل على الكود من المدير وأدخله أدناه:"
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
                            label = "كود التحقق (6 أرقام) *",
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
                if (resetStep == 1) {
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
                            if (codeClean != generatedResetCode.trim()) {
                                resetErrorMessage = "كود التحقق غير صحيح. يرجى التأكد من الرمز المدخل."
                                return@GlassButton
                            }
                            if (passClean.length < 4) {
                                resetErrorMessage = "كلمة المرور الجديدة يجب أن تكون 4 خانات على الأقل"
                                return@GlassButton
                            }
                            val user = targetResetUser ?: return@GlassButton
                            isResetLoading = true
                            resetErrorMessage = null
                            viewModel.confirmPasswordReset(
                                userId = user.id,
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
fun GoogleIdentityLogoButton(
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        Surface(
            onClick = {
                if (!isLoading) {
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
            color = Color.White,
            border = BorderStroke(
                2.dp,
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
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(62.dp)
                .testTag("google_sign_in_button")
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = Color(0xFF4285F4),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = "G",
                        color = Color(0xFF4285F4),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp
                    )
                }
            }
        }
        Text(
            text = "Google",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
