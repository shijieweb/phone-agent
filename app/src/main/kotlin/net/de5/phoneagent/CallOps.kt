package net.de5.phoneagent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Dialer / call control for `call` (SPEC.md section 3.13).
 *
 * Three different capabilities with three different grants:
 *
 *  - opening the dialer with the number filled in needs nothing (ACTION_DIAL);
 *  - placing the call directly needs CALL_PHONE (ACTION_CALL);
 *  - answering or ending needs ANSWER_PHONE_CALLS, which pm grant can give.
 *
 * The command always reports which of the three actually happened, so "已拨号"
 * is never printed when all that happened was a dialer being opened.
 */
internal object CallOps {

    fun dial(context: Context, number: String): String {
        val uri = Uri.fromParts("tel", number, null)
        if (Admin.granted(context, android.Manifest.permission.CALL_PHONE)) {
            return try {
                val intent = Intent(Intent.ACTION_CALL, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AgentLog.add("call", "已拨号 " + number)
                CallPlan.dialed(number)
            } catch (t: Throwable) {
                CallPlan.failure(describe(t))
            }
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLog.add("call", "已打开拨号界面 " + number)
            "已打开拨号界面 " + number + "（未授予 CALL_PHONE，需手动按拨号键；" +
                "自动拨号：adb shell pm grant net.de5.phoneagent android.permission.CALL_PHONE）"
        } catch (t: Throwable) {
            CallPlan.failure(describe(t))
        }
    }

    fun answer(context: Context): String {
        val telecom = try {
            context.getSystemService(TelecomManager::class.java)
        } catch (t: Throwable) {
            null
        } ?: return Admin.ERROR_PREFIX + CallPlan.failure("本机没有 TelecomManager")
        return try {
            telecom.acceptRingingCall()
            AgentLog.add("call", "已接听")
            CallPlan.answered()
        } catch (t: SecurityException) {
            Admin.ERROR_PREFIX + CallPlan.answerDenied()
        } catch (t: Throwable) {
            Admin.ERROR_PREFIX + CallPlan.failure(describe(t))
        }
    }

    fun hangUp(context: Context): String {
        val telecom = try {
            context.getSystemService(TelecomManager::class.java)
        } catch (t: Throwable) {
            null
        } ?: return Admin.ERROR_PREFIX + CallPlan.failure("本机没有 TelecomManager")
        return try {
            if (!telecom.isInCall) {
                return "当前没有通话（无需挂断）"
            }
            telecom.endCall()
            AgentLog.add("call", "已挂断")
            CallPlan.hungUp()
        } catch (t: SecurityException) {
            Admin.ERROR_PREFIX + CallPlan.answerDenied()
        } catch (t: Throwable) {
            Admin.ERROR_PREFIX + CallPlan.failure(describe(t))
        }
    }

    /**
     * Read or set the speakerphone.
     *
     * Two halves, and both are needed: `setSpeakerphoneOn` alone is ignored
     * while the audio mode is MODE_NORMAL, so setting it also forces
     * MODE_IN_COMMUNICATION — that is the mode a call UI would be in. Turning it
     * off restores MODE_NORMAL, otherwise the route stays hijacked after the
     * call ends and the next app gets no audio.
     *
     * [on] null is the report form: nothing is changed.
     */
    fun speaker(context: Context, on: Boolean?): String {
        val audio = try {
            context.getSystemService(AudioManager::class.java)
        } catch (t: Throwable) {
            null
        } ?: return Admin.ERROR_PREFIX + "本机没有 AudioManager"
        val offHook = isOffHook(audio)
        if (on == null) {
            val current = try {
                audio.isSpeakerphoneOn
            } catch (t: Throwable) {
                return Admin.ERROR_PREFIX + "读取免提状态失败: " + describe(t)
            }
            return CallPlan.speaker(current, offHook)
        }
        return try {
            if (on) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.isSpeakerphoneOn = true
            } else {
                audio.isSpeakerphoneOn = false
                audio.mode = AudioManager.MODE_NORMAL
            }
            AgentLog.add("call", if (on) "已开启免提" else "已关闭免提")
            CallPlan.speakerSet(on, offHook)
        } catch (t: SecurityException) {
            Admin.ERROR_PREFIX + CallPlan.speakerDenied()
        } catch (t: Throwable) {
            Admin.ERROR_PREFIX + "切换免提失败: " + describe(t)
        }
    }

    /** True when the audio route says a call is up; no permission needed. */
    fun isOffHook(audio: AudioManager): Boolean {
        val mode = try {
            audio.mode
        } catch (t: Throwable) {
            AudioManager.MODE_NORMAL
        }
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    /**
     * Off-hook is readable without any permission (the audio mode changes), but
     * "ringing" lives behind READ_PHONE_STATE, so it degrades to false rather
     * than failing the whole command.
     */
    fun state(context: Context): String {
        val audio = try {
            context.getSystemService(AudioManager::class.java)
        } catch (t: Throwable) {
            null
        }
        val mode = audio?.mode ?: AudioManager.MODE_NORMAL
        val offHook = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
        var ringing = false
        if (Admin.granted(context, android.Manifest.permission.READ_PHONE_STATE)) {
            val telephony = try {
                context.getSystemService(TelephonyManager::class.java)
            } catch (t: Throwable) {
                null
            }
            ringing = try {
                telephony?.callState == TelephonyManager.CALL_STATE_RINGING
            } catch (t: Throwable) {
                false
            }
        }
        return CallPlan.state(offHook, ringing)
    }
}
