package com.example.util

import android.content.Context
import android.content.SharedPreferences
import com.example.PmpScreen

object BottomNavPreferences {

    private const val PREFS_NAME = "pmp_bottom_nav_prefs"
    private const val KEY_PREFIX = "nav_order_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun userKey(userCode: String?): String {
        val clean = userCode?.trim()?.lowercase() ?: "default_employee"
        return KEY_PREFIX + clean.ifBlank { "default_employee" }
    }

    /**
     * Gets the customized ordered list of allowed screens for the employee.
     * Any new allowed screens not yet in the user's custom order are appended at the end.
     */
    fun getOrderedScreens(
        context: Context,
        userCode: String?,
        allowedScreens: List<PmpScreen>
    ): List<PmpScreen> {
        val savedString = getPrefs(context).getString(userKey(userCode), null)
        if (savedString.isNullOrBlank()) {
            return allowedScreens
        }

        val savedRoutes = savedString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val screenMap = allowedScreens.associateBy { it.route }

        val result = mutableListOf<PmpScreen>()
        // 1. Add saved screens in their saved order if still allowed
        for (route in savedRoutes) {
            val screen = screenMap[route]
            if (screen != null && screen !in result) {
                result.add(screen)
            }
        }
        // 2. Append any allowed screens that weren't in the saved preferences
        for (screen in allowedScreens) {
            if (screen !in result) {
                if (screen == PmpScreen.PURCHASES && PmpScreen.TREASURY in result) {
                    val treasuryIdx = result.indexOf(PmpScreen.TREASURY)
                    result.add(treasuryIdx + 1, screen)
                } else {
                    result.add(screen)
                }
            }
        }
        return result
    }

    /**
     * Saves the custom order of screens for this employee.
     */
    fun saveOrderedScreens(
        context: Context,
        userCode: String?,
        orderedScreens: List<PmpScreen>
    ) {
        val routesString = orderedScreens.joinToString(",") { it.route }
        getPrefs(context).edit().putString(userKey(userCode), routesString).apply()
    }

    /**
     * Resets order to system default for this employee.
     */
    fun resetOrder(context: Context, userCode: String?) {
        getPrefs(context).edit().remove(userKey(userCode)).apply()
    }
}
