package com.robot.simulator.bridge

import android.content.Context
import android.os.Process

object BridgeAccessPolicy {
    private const val PREFERENCES = "bridge_access"
    private const val KEY_GLOBAL_MODE = "global_mode"
    private const val KEY_TARGET_PACKAGE = "target_package"

    private val blockedPackages = setOf(
        BridgeContract.SIMULATOR_PACKAGE,
        "com.robot.simulator.hook",
        "io.github.huskydg.magisk",
        "org.lsposed.manager",
        "android",
        "com.android.systemui",
        "com.mumu.launcher",
    )

    fun isGlobalMode(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_GLOBAL_MODE, true)

    fun targetPackage(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_TARGET_PACKAGE, BridgeContract.DEFAULT_TEST_TARGET)
            .orEmpty()

    fun save(context: Context, globalMode: Boolean, targetPackage: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GLOBAL_MODE, globalMode)
            .putString(KEY_TARGET_PACKAGE, targetPackage.trim())
            .apply()
    }

    fun isCallerAllowed(context: Context, uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (packages.isEmpty() || packages.any(blockedPackages::contains)) return false
        return if (isGlobalMode(context)) {
            uid >= Process.FIRST_APPLICATION_UID
        } else {
            targetPackage(context) in packages
        }
    }
}
