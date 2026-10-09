package com.freeturn.app.service
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.freeturn.app.R
/**
 * CREATE_SHORTCUT для автоматизаторов (MacroDroid, Tasker): отдаёт готовый intent ярлыка
 * Start/Stop. Сама ничего не запускает. Intent целится в ProxyShortcutActivity (алиас),
 * поэтому ярлык работает, только когда включено "Внешнее управление".
 */
abstract class CreateShortcutActivity : Activity() {
    protected abstract val proxyAction: String
    protected abstract val labelRes: Int
    protected abstract val iconRes: Int
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_CREATE_SHORTCUT) {
            runDirect()
            finish()
            return
        }
        val launch = Intent(proxyAction)
            .setClassName(packageName, ENTRY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = Intent()
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(labelRes))
            .putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, iconRes)
            )
        setResult(RESULT_OK, result)
        if (!ExternalControl.isEnabled(this)) {
            Toast.makeText(this, R.string.external_control_hint, Toast.LENGTH_LONG).show()
        }
        finish()
    }
    // Прямой запуск (Shortcut Maker, Activity Launcher): ведёт себя как сам ярлык.
    private fun runDirect() {
        if (ExternalControl.isEnabled(this)) {
            startActivity(
                Intent(this, ProxyTrampolineActivity::class.java).setAction(proxyAction)
            )
        } else {
            Toast.makeText(this, R.string.external_control_hint, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val ENTRY_CLASS = "com.freeturn.app.service.ProxyShortcutActivity"
    }
}
class CreateStartShortcutActivity : CreateShortcutActivity() {
    override val proxyAction = ProxyActions.START
    override val labelRes = R.string.shortcut_start_short
    override val iconRes = R.drawable.play_arrow_24px
}
class CreateStopShortcutActivity : CreateShortcutActivity() {
    override val proxyAction = ProxyActions.STOP
    override val labelRes = R.string.shortcut_stop_short
    override val iconRes = R.drawable.stop_24px
}
