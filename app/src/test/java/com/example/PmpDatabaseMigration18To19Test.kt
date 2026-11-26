package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PmpDatabaseMigration18To19Test {

    private val dbName = "pmp_migration_18_19_test.db"
    private var migratedDb: PmpDatabase? = null

    @After
    fun tearDown() {
        migratedDb?.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 18 to 19 creates operations tables and preserves room schema validation`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        val helper = object : SQLiteOpenHelper(context, dbName, null, 11) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS invoices (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceNo` TEXT NOT NULL DEFAULT '')")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        helper.writableDatabase.close()
        helper.close()

        val db = Room.databaseBuilder(context, PmpDatabase::class.java, dbName)
            .addMigrations(
                PmpDatabase.MIGRATION_11_12,
                PmpDatabase.MIGRATION_12_13,
                PmpDatabase.MIGRATION_13_14,
                PmpDatabase.MIGRATION_14_15,
                PmpDatabase.MIGRATION_15_16,
                PmpDatabase.MIGRATION_16_17,
                PmpDatabase.MIGRATION_17_18,
                PmpDatabase.MIGRATION_18_19
            )
            .allowMainThreadQueries()
            .build()
        migratedDb = db

        val ops = db.operationsDao()
        ops.upsertShiftLog(
            ShiftProductionLogEntity(
                syncUuid = "SHF-TEST-1",
                shiftDate = "2026-11-26",
                lineGroup = "3 خطوط",
                engineerName = "م. محمد عبدالباسط",
                producedCartons = 500
            )
        )
        ops.upsertSeqaofaEntry(
            SeqaofaEntryEntity(
                syncUuid = "SEQ-TEST-1",
                entryDate = "2026-11-26",
                custodianName = "عمر عبدالوهاب",
                amountSdg = 1000000.0
            )
        )
        assertEquals(1, ops.getShiftLogsDirect().size)
        assertEquals(1, ops.getSeqaofaEntriesDirect().size)
    }
}
