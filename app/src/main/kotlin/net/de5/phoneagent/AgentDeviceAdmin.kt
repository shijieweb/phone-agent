package net.de5.phoneagent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device-admin / Device-Owner receiver.
 *
 * Deliberately inert: it only records that the admin state changed. No UI, no
 * toast, no notification - the operator provisions this phone over the relay
 * (`adb shell dpm set-device-owner ...` before the app is ever opened), and an
 * on-screen prompt there would just get in the way of a kiosk device.
 *
 * Every callback is wrapped: a crash inside a receiver is reported to the system
 * as an ANR-class fault on this package, and losing Device Owner because of a
 * logging bug would cost a factory reset to recover.
 */
class AgentDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        safely { AgentLog.add("admin", "设备管理器已启用") }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        safely { AgentLog.add("admin", "设备管理器已停用") }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        safely { AgentLog.add("admin", "收到停用请求") }
        return "停用后远程静默安装/设置/重启等能力会失效"
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // A receiver must never throw: the framework would log a crash of
            // this package and there is nothing to recover here.
        }
    }
}
