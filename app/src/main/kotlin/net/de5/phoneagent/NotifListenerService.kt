package net.de5.phoneagent

import android.service.notification.NotificationListenerService

/**
 * The only way to read other apps' notifications (SPEC.md section 3.8).
 *
 * A notification listener is not a runtime permission: the operator either
 * grants "通知使用权" in settings or the command
 * `adb shell cmd notification allow_listener net.de5.phoneagent/net.de5.phoneagent.NotifListenerService`
 * is run. [NotifOps] therefore reports "not connected" rather than "denied",
 * and always prints that exact adb line.
 */
class NotifListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotifOps.attach(this)
        AgentLog.add("notif", "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        NotifOps.detach(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        NotifOps.detach(this)
        super.onDestroy()
    }
}
