package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shift_production_logs",
    indices = [Index(value = ["syncUuid"], unique = true), Index(value = ["shiftDate"])]
)
data class ShiftProductionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val shiftDate: String,
    val shiftCode: String = "الصباحية",
    val lineGroup: String,
    val engineerName: String,
    val engineerUsername: String = "",
    val activeLinesCount: Int = 3,
    val workersPresent: Int = 10,
    val workersRequired: Int = 10,
    val targetCartons: Int = 500,
    val producedCartons: Int = 0,
    val productType: String = "1/16",
    val wasteCans: Int = 0,
    val maxWasteAllowed: Int = 25,
    val technicalDowntimeMin: Int = 0,
    val materialDowntimeMin: Int = 0,
    val downtimeReason: String = "",
    val qualityStatus: String = "مطابق",
    val qualityReviewedBy: String = "",
    val branch: String = "فرع مصر والمصنع (EG-001)",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "press_machines",
    indices = [Index(value = ["syncUuid"], unique = true)]
)
data class PressMachineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val machineCode: String,
    val machineName: String,
    val lineCategory: String = "عادي",
    val pressStatus: String = "يعمل بكفاءة",
    val assignedOperator: String = "",
    val lastMaintenanceDate: String = "",
    val notes: String = "",
    val branch: String = "فرع مصر والمصنع (EG-001)",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tool_custodies",
    indices = [Index(value = ["syncUuid"], unique = true)]
)
data class ToolCustodyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val toolCode: String,
    val toolName: String,
    val department: String = "التعبئة",
    val custodianName: String,
    val custodianUsername: String = "",
    val quantityIssued: Int = 1,
    val quantityReturned: Int = 0,
    val damagedCount: Int = 0,
    val status: String = "مسلمة",
    val issuedDate: String = "",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "daily_checklists",
    indices = [Index(value = ["syncUuid"], unique = true), Index(value = ["dateKey"])]
)
data class DailyChecklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val dateKey: String,
    val roleKey: String,
    val assigneeName: String,
    val assigneeUsername: String = "",
    val title: String,
    val metricTarget: String = "",
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val note: String = "",
    val branch: String = "فرع مصر والمصنع (EG-001)",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "employee_tasks",
    indices = [Index(value = ["syncUuid"], unique = true), Index(value = ["dueDate"])]
)
data class EmployeeTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val taskCode: String,
    val title: String,
    val description: String = "",
    val roleKey: String = "GENERAL_MANAGER",
    val assigneeName: String,
    val assigneeUsername: String = "",
    val assignedBy: String = "",
    val priority: String = "عاجل",
    val status: String = "قيد الانتظار",
    val dueDate: String,
    val targetValue: String = "",
    val achievedValue: String = "",
    val hasDeviation: Boolean = false,
    val branch: String = "فرع مصر والمصنع (EG-001)",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "seqaofa_entries",
    indices = [Index(value = ["syncUuid"], unique = true), Index(value = ["entryDate"])]
)
data class SeqaofaEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val entryDate: String,
    val hub: String = "حلفا",
    val custodianName: String,
    val direction: String = "وارد",
    val amountSdg: Double = 0.0,
    val exchangeRate: Double = 0.0,
    val equivalentEgp: Double = 0.0,
    val purpose: String = "",
    val referenceCode: String = "",
    val recordedBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "in_transit_shipments",
    indices = [Index(value = ["syncUuid"], unique = true), Index(value = ["shipmentCode"])]
)
data class InTransitShipmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val shipmentCode: String,
    val dispatchDate: String,
    val destinationHub: String = "حلفا",
    val driverOrCarrier: String = "",
    val cartons116: Int = 0,
    val cartons1Kg: Int = 0,
    val cartonsGallon: Int = 0,
    val customsSdg: Double = 0.0,
    val freightEgp: Double = 0.0,
    val status: String = "خرج من مصنع مصر",
    val receivedBy: String = "",
    val receivedAt: Long? = null,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "financial_budgets",
    indices = [Index(value = ["syncUuid"], unique = true)]
)
data class FinancialBudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val periodMonth: String,
    val category: String,
    val department: String = "",
    val currency: String = "EGP",
    val plannedAmount: Double = 0.0,
    val actualAmount: Double = 0.0,
    val deviationPercent: Double = 0.0,
    val maxAllowedDeviationPercent: Double = 5.0,
    val supervisorName: String = "",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "employee_rewards",
    indices = [Index(value = ["syncUuid"], unique = true)]
)
data class EmployeeRewardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncUuid: String,
    val periodLabel: String,
    val employeeName: String,
    val employeeUsername: String = "",
    val roleKey: String = "GENERAL_MANAGER",
    val roleTitle: String = "",
    val completionPercent: Double = 0.0,
    val deviationPercent: Double = 0.0,
    val errorCount: Int = 0,
    val innovationCount: Int = 0,
    val bonusTier: String = "غير مستوفٍ للشرط",
    val bonusMonths: Int = 0,
    val evaluatedBy: String = "",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
