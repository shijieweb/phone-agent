package net.de5.phoneagent

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Snapshot of the active notifications, formatted by [NotifPlan].
 *
 * The listener is a separate process component, so the service instance is
 * published through a static reference: the executor asks "what is showing now"
 * and gets an empty list when nobody granted the access, which is the honest
 * answer.
 */
internal object NotifOps {

    @Volatile
    private var service: NotifListenerService? = null

    fun attach(listener: NotifListenerService) {
        service = listener
    }

    fun detach(listener: NotifListenerService) {
        if (service === listener) service = null
    }

    /** True when the listener is bound; false means "no grant", not "no notifications". */
    fun connected(): Boolean = service != null

    /**
     * Newest first, ongoing included (they are often the interesting ones: a
     * call, a navigation prompt, a download that is stuck).
     */
    fun snapshot(): List<NotifPlan.Item> {
        val listener = service ?: return emptyList()
        val active: Array<StatusBarNotification> = try {
            listener.activeNotifications ?: return emptyList()
        } catch (t: Throwable) {
            return emptyList()
        }
        val items = ArrayList<NotifPlan.Item>(active.size)
        for (sbn in active) {
            val notification = sbn.notification ?: continue
            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            items.add(
                NotifPlan.Item(
                    sbn.packageName ?: "?",
                    title ?: "",
                    text ?: "",
                    sbn.postTime,
                    (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                )
            )
        }
        items.sortByDescending { it.timeMillis }
        return items
    }
}
