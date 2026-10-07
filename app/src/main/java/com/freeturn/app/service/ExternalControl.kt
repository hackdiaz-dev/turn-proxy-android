package com.freeturn.app.service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
/**
 * Внешний вход для автоматизаторов (MacroDroid, Tasker, Shortcut Maker): activity-alias
 * ProxyShortcutActivity. По умолчанию выключен, состояние хранится в самом компоненте.
 */
object ExternalControl {
    private fun alias(context: Context) =
        ComponentName(context, "com.freeturn.app.service.ProxyShortcutActivity")
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }
}
