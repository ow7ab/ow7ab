package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.RoomTransactionTrace
import com.example.util.TraceStatus
import com.example.util.TransactionStage

@Composable
fun TransactionDiagnosticsDialog(
    traces: List<RoomTransactionTrace>,
    onClear: () -> Unit,
    onWipeServer: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("الكل") }
    val filteredTraces = remember(traces, selectedFilter) {
        when (selectedFilter) {
            "أخطاء فقط" -> traces.filter { it.status == TraceStatus.FAILURE || it.stage == TransactionStage.SYNC_BROKEN }
            "معاملات Room" -> traces.filter {
                it.stage in listOf(
                    TransactionStage.TX_BEGIN,
                    TransactionStage.ROOM_INSERT,
                    TransactionStage.ROOM_UPDATE,
                    TransactionStage.ROOM_DELETE,
                    TransactionStage.ROOM_TX_COMMIT,
                    TransactionStage.ROOM_TX_ROLLBACK
                )
            }
            "مزامنة السحابة" -> traces.filter {
                it.stage in listOf(
                    TransactionStage.SYNC_DISPATCH,
                    TransactionStage.SYNC_ACKNOWLEDGED,
                    TransactionStage.SYNC_BROKEN
                )
            }
            "استعلامات" -> traces.filter { it.stage == TransactionStage.FETCH_QUERY }
            else -> traces
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .heightIn(max = 680.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Analytics, contentDescription = null, tint = PmpCyan)
                    Column {
                        Text(
                            text = "تشخيص مسار المعاملات والمزامنة (Room & Cloud)",
                            color = TextDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "${traces.size} حركة متتبعة • فحص فوري لانقطاع البيانات المالية",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Filter chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("الكل", "أخطاء فقط", "معاملات Room", "مزامنة السحابة", "استعلامات").forEach { filter ->
                        val isSelected = selectedFilter == filter
                        Surface(
                            onClick = { selectedFilter = filter },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) PmpCyan.copy(alpha = 0.15f) else PmpNavyCard,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) PmpCyan else PmpNavyBorder
                            )
                        ) {
                            Text(
                                text = filter,
                                color = if (isSelected) PmpCyan else TextMuted,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (filteredTraces.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد سجلات تتبع مسجلة في هذا التصنيف حالياً.\nقم بإضافة حركة مالية أو تصفح كشف الحساب لرؤية التتبع اللحظي.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredTraces, key = { it.id + it.timestamp }) { trace ->
                            TraceItemCard(trace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButton(
                    text = "حذف جميع السجلات من السيرفر والبدء من جديد",
                    onClick = onWipeServer,
                    icon = Icons.Default.DeleteSweep,
                    gradient = PmpBrandRedGradient,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassButton(
                        text = "مسح سجل التتبع",
                        onClick = onClear,
                        icon = Icons.Default.DeleteOutline,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        text = "إغلاق",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        containerColor = PmpNavySurface
    )
}

@Composable
fun TraceItemCard(trace: RoomTransactionTrace) {
    val stageColor = when (trace.stage) {
        TransactionStage.ROOM_TX_COMMIT -> PmpGreen
        TransactionStage.ROOM_TX_ROLLBACK -> PmpRed
        TransactionStage.TX_BEGIN -> PmpCyan
        TransactionStage.ROOM_INSERT, TransactionStage.ROOM_UPDATE, TransactionStage.ROOM_DELETE -> PmpBlueLight
        TransactionStage.SYNC_DISPATCH -> PmpGold
        TransactionStage.SYNC_ACKNOWLEDGED -> PmpGreen
        TransactionStage.SYNC_BROKEN -> PmpRed
        TransactionStage.FETCH_QUERY -> PmpPurple
    }

    val stageLabel = when (trace.stage) {
        TransactionStage.TX_BEGIN -> "بدء معاملة Room"
        TransactionStage.ROOM_INSERT -> "إدراج سجل Room"
        TransactionStage.ROOM_UPDATE -> "تعديل سجل Room"
        TransactionStage.ROOM_DELETE -> "حذف سجل Room"
        TransactionStage.ROOM_TX_COMMIT -> "تثبيت Room بنجاح"
        TransactionStage.ROOM_TX_ROLLBACK -> "تراجع وفشل Room"
        TransactionStage.FETCH_QUERY -> "استعلام قراءة Room"
        TransactionStage.SYNC_DISPATCH -> "إرسال إلى السحابة"
        TransactionStage.SYNC_ACKNOWLEDGED -> "تأكيد السيرفر بنجاح"
        TransactionStage.SYNC_BROKEN -> "انقطاع المزامنة"
    }

    val statusIcon = when (trace.status) {
        TraceStatus.SUCCESS -> Icons.Default.CheckCircle
        TraceStatus.FAILURE -> Icons.Default.Error
        TraceStatus.WARNING -> Icons.Default.Warning
        TraceStatus.IN_PROGRESS -> Icons.Default.HourglassTop
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        borderColor = if (trace.status == TraceStatus.FAILURE) PmpRed else null
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(statusIcon, contentDescription = null, tint = stageColor, modifier = Modifier.size(16.dp))
                    Text(
                        text = stageLabel,
                        color = stageColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "[${trace.operation}]",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (trace.durationMs > 0) {
                        Text(
                            text = "${trace.durationMs}ms",
                            color = PmpCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = trace.formattedTime,
                        color = TextSubtle,
                        fontSize = 10.sp
                    )
                }
            }

            Text(
                text = trace.details,
                color = TextDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            if (!trace.diagnosticsHint.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = (if (trace.status == TraceStatus.FAILURE) PmpRed else PmpGold).copy(alpha = 0.1f),
                    border = BorderStroke(0.5.dp, if (trace.status == TraceStatus.FAILURE) PmpRed else PmpGold),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = if (trace.status == TraceStatus.FAILURE) PmpRed else PmpGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = trace.diagnosticsHint,
                            color = if (trace.status == TraceStatus.FAILURE) PmpRed else TextDark,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            if (!trace.errorMessage.isNullOrBlank()) {
                Text(
                    text = "سبب الخطأ: ${trace.errorMessage}",
                    color = PmpRed,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ServerWipeDangerDialog(
    onConfirmWithPassword: (password: String, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var managerPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .border(2.5.dp, Color(0xFFDC2626), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFFEF2F2),
        title = {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFDC2626),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "تحذير خطير جداً: تصفير وحذف السيرفر!",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.5.sp
                        )
                        Text(
                            text = "إجراء حساس لا يمكن التراجع عنه بعد التنفيذ",
                            color = Color(0xFFFECACA),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFEE2E2),
                    border = BorderStroke(1.5.dp, Color(0xFFEF4444)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "⚠️ تنبيه بخطورة الضغط والمتابعة:",
                            color = Color(0xFF991B1B),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            text = "أنت على وشك مسح وحذف جميع السجلات والمعاملات المالية، الفواتير، حركات المخازن، المشتريات، التحويلات، وإقفالات اليومية من السيرفر السحابي ومحلياً بشكل نهائي!",
                            color = Color(0xFF7F1D1D),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp
                        )
                        Text(
                            text = "• لا تتابع إلا في حالة الجرد الشامل والبدء من الصفر.\n• يشترط إدخال كلمة المرور الخاصة بمدير النظام للمتابعة.",
                            color = Color(0xFFB91C1C),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                GlassTextField(
                    value = managerPassword,
                    onValueChange = {
                        managerPassword = it
                        errorMessage = null
                    },
                    label = "كلمة المرور الخاصة بمدير النظام *",
                    placeholder = "أدخل كلمة مرور أو PIN مدير النظام للتأكيد...",
                    leadingIcon = Icons.Default.Lock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manager_wipe_password_input")
                )

                errorMessage?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFDC2626),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(
                                text = err,
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (managerPassword.trim().isEmpty()) {
                            errorMessage = "يرجى إدخال كلمة المرور الخاصة بمدير النظام أولاً"
                            return@Button
                        }
                        onConfirmWithPassword(managerPassword.trim()) { err ->
                            errorMessage = err
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_danger_wipe_server_button")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تأكيد كلمة المرور وحذف سجلات السيرفر نهائياً",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.5.dp, Color(0xFF475569)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "إلغاء وتراجع فوري (عدم الحذف)",
                        color = Color(0xFF1E293B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    )
}

