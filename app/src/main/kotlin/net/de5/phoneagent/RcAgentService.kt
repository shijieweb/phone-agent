package net.de5.phoneagent

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that holds the outbound long-poll to the relay.
 * Placeholder: real loop is described in SPEC.md §2 and §7.
 */
class RcAgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null
}
