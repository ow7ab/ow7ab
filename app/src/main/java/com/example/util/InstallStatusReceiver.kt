package com.example.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * مستقبل إشعارات حالة جلسات PackageInstaller عند اكتمال أو إلغاء أو تعذر التثبيت الفعلي للحزمة
 */
class InstallStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.example.ACTION_INSTALL_STATUS"
        private const val TAG = "InstallStatusReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmIntent != null) {
                    runCatching {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                    }.onFailure { err ->
                        Log.w(TAG, "Unable to launch installer confirmation intent: ${err.message}")
                    }
                }
                runCatching {
                    UpdateManager.getInstance(context).onPackageInstallerStatus(status, message)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "اكتملت جلسة تثبيت الحزمة من النظام؛ جارٍ التحقق من PackageManager.")
                runCatching {
                    UpdateManager.getInstance(context).onPackageInstallerStatus(status, message)
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.w(TAG, "ألغى المستخدم عملية تثبيت التحديث (الرمز: $status, التفاصيل: $message)")
                runCatching {
                    UpdateManager.getInstance(context).onPackageInstallerStatus(status, message)
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "فشل تثبيت التحديث (الرمز: $status, التفاصيل: $message)")
                runCatching {
                    UpdateManager.getInstance(context).onPackageInstallerStatus(status, message)
                }
            }
        }
    }
}
