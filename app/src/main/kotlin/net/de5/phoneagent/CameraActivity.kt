package net.de5.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputFilter
import android.text.InputType
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 「摄像头看图」页（v0.9.0-pad12）—— 手机主人举着手机对着一处，打一句话按「发送」，
 * 这一帧和这句话就送到中转站，那边的操作员看得见（SPEC.md 第 3.29 节）。
 *
 * ## 为什么不用截屏
 *
 * 这个 App 取画面一直靠 Screenshot（screencap / AccessibilityService.takeScreenshot）。
 * 相机预览在 Android 上是**硬件叠加层**：GPU 直接合成到显示控制器，绕过了 SurfaceFlinger
 * 的合成缓冲，所以截屏截到的是一块黑（或上一次的残影），不是镜头里的画面。这不是权限
 * 问题，加多少权限都截不到。要拿相机画面只有一条正路：Camera2 + ImageReader(JPEG)，
 * 让相机把一帧 JPEG 直接写进我们自己的缓冲区。
 *
 * ## 为什么预览和拍照要分两个尺寸
 *
 * 预览要的是「看得见、不卡」——挑一个 16:9 的尺寸；拍照要的是「够看清、传得动」——挑
 * 接近 1280x720 的 JPEG 尺寸，再按 CamPlan.MAX_PX / CamPlan.JPEG_QUALITY 重压一次。
 * 两个尺寸都从 StreamConfigurationMap 里挑，**都不取最大值**：这台机器最大能到 4000x3000，
 * 一帧 2 MB 在手机上行链路里要传好几秒，而操作员那边看的是 1280 宽的屏。
 *
 * ## 线程
 *
 * 相机的所有调用（open / createCaptureSession / capture / close）都在自己的 HandlerThread
 * 上，这是 Camera2 的要求：CameraDevice 的回调线程必须是带 Looper 的单一线程，主线程上做
 * 这些会卡界面，而且 close 与 capture 抢同一个对象会抛 IllegalStateException。解码、重压、
 * 上传在另一个普通线程上跑——那几秒里相机还得继续预览。
 *
 * 这一页不导出（exported = false，不进最近任务）：只有配置页那个按钮能进来。
 */
class CameraActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var previewView: TextureView

    /** Shown over the preview while the camera is released (v0.11.4-pad12). */
    private lateinit var camOffView: TextView
    private lateinit var inputView: EditText
    private lateinit var statusView: TextView
    private lateinit var sendButton: Button

    /** The top-right switch; its label follows [cameraOn]. */
    private lateinit var cameraButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())

    /** Lazy so the vibrator is looked up once, on the first send rather than at launch. */
    private val haptics: Haptics by lazy { Haptics(this) }

    private val camThread = HandlerThread("cam-look")
    private lateinit var camHandler: Handler

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    /** Sensor orientation, read when the camera opens; used to turn the frame upright. */
    private var sensorOrientation = 0

    /** True between the tap on 发送 and the answer: blocks a second capture. */
    @Volatile
    private var busy = false

    /**
     * Whether the camera is capturing at all (v0.11.4-pad12).
     *
     * This is the switch the top-right button owns, and it is *not* the same fact as
     * "this page is visible": the page can be open with the camera released, and
     * that is the state the owner asked for -- 关闭摄像头 means "stop taking pictures
     * so the next 发送 goes out without one", not "leave". While it is false the
     * preview holds a grey sentence instead of a frame, and [send] takes the
     * text-only path. Volatile because [send] reads it from a background thread.
     */
    @Volatile
    private var cameraOn = true

    /**
     * The seq of the newest note whose receipt is still being waited on
     * (v0.11.5-pad12).
     *
     * A single slot, not a queue: the owner cares about the note he just sent, and
     * the ack thread of an older send compares this before every write and returns
     * the moment it no longer owns it. -1 means "nothing in flight" and is also what
     * [onDestroy] writes to retire the thread without touching its sleep.
     */
    @Volatile
    private var ackSeq = -1L

    /** The ack thread, so [onDestroy] can interrupt its sleep instead of leaking it. */
    @Volatile
    private var ackThread: Thread? = null

    /** True once the surface listener is installed, so it is not installed twice. */
    private var started = false

    /** True while openCamera is in flight, so a surface callback cannot open twice. */
    @Volatile
    private var opening = false

    /** The sentence that goes with the frame currently in flight. */
    @Volatile
    private var pendingText: String = ""

    /**
     * True while the operator has been told this page is showing the camera.
     *
     * It exists so the open and close notes come in pairs: onResume can run twice
     * without an onPause in between (a permission dialog returning), and a second
     * cam-open would read to the operator as a second opening of a camera that
     * never closed.
     */
    @Volatile
    private var modeOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // No title line any more (v0.11.3-pad12): the theme drops the ActionBar so the
        // preview gets those pixels back, and a label nothing displays is dead code.

        // The phone is held up at something and the owner talks into it; the screen
        // going dark mid-sentence would end the whole interaction.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(24), dp(24), dp(16))

        // 关闭摄像头 sits above the preview, right-aligned and slim (v0.11.3-pad12):
        // the owner may want to send a sentence with no picture at all, and leaving
        // the page used to be the 返回 button at the bottom, which competed with 发送
        // for the same row. A small button at the edge keeps the frame unobstructed.
        // Since v0.11.4 it is a switch, not a way out -- see [toggleCamera].
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.END
        cameraButton = Button(this)
        cameraButton.text = CamPlan.CLOSE_CAMERA_TEXT
        cameraButton.isAllCaps = false
        cameraButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        cameraButton.setPadding(dp(12), dp(4), dp(12), dp(4))
        cameraButton.minHeight = 0
        cameraButton.minimumHeight = 0
        cameraButton.setOnClickListener { toggleCamera() }
        topBar.addView(
            cameraButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(topBar, matchWidth())

        // weight 1: the preview takes every pixel the text box and the buttons do
        // not need. A smaller preview is what makes the owner guess what is in frame.
        //
        // The TextureView keeps the weight and the FrameLayout is only a host, so the
        // proportions are exactly what they were before v0.11.4: one more layer that
        // measures itself around its single child changes no pixel. The placeholder is
        // a sibling *over* the preview (v0.11.4-pad12) because a released camera leaves
        // the last frame on screen -- a frozen picture of a room reads as "still on",
        // and this sentence is what says it is not.
        previewView = TextureView(this)
        camOffView = TextView(this)
        camOffView.text = CamPlan.CAM_OFF_TEXT
        camOffView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_NORMAL)
        camOffView.setTextColor(COLOR_IDLE)
        camOffView.gravity = Gravity.CENTER
        camOffView.visibility = View.GONE
        val previewHost = FrameLayout(this)
        previewHost.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        previewHost.addView(
            camOffView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            previewHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        )

        statusView = TextView(this)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_NORMAL)
        statusView.setTextColor(COLOR_IDLE)
        statusView.setPadding(0, dp(8), 0, dp(8))
        root.addView(statusView, matchWidth())

        inputView = EditText(this)
        inputView.hint = HINT_TEXT
        inputView.setSingleLine(false)
        inputView.minLines = 2
        inputView.maxLines = 4
        // No TYPE_TEXT_FLAG_MULTI_LINE on purpose (v0.11.3-pad12): with that flag every
        // IME turns its action key into "insert a newline" and never calls the editor
        // action, so Enter would break the line instead of sending. The box still
        // wraps and still shows two to four lines -- setSingleLine(false) is what does
        // the visual part; the flag only controls what the Enter key means.
        inputView.inputType = InputType.TYPE_CLASS_TEXT
        // The IME's own voice key types into whatever has focus, so this box is the
        // whole speech-to-text path: no recogniser, no network, no extra permission.
        inputView.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(CamPlan.MAX_TEXT))
        // Enter sends instead of inserting a newline (v0.11.3-pad12). Both listeners
        // are needed: some IMEs report the soft key as an editor action, others as a
        // raw ENTER key event, and the owner's own voice IME is in the second camp.
        // A double fire is harmless -- send() returns at once while busy.
        inputView.imeOptions = EditorInfo.IME_ACTION_SEND
        inputView.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        inputView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                send()
                true
            } else {
                false
            }
        }
        root.addView(inputView, matchWidth())

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        // 发送 only: since v0.11.4 the top-right button is a camera switch, so leaving
        // this page is the system back gesture and nothing else.
        sendButton = addButton(buttons, SEND_TEXT, 1.0f) { send() }
        root.addView(buttons, matchWidth())

        setContentView(root)

        camThread.start()
        camHandler = Handler(camThread.looper)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            statusView.text = NEED_PERMISSION_TEXT
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            statusView.text = ""
            startCamera()
        } else {
            // Nothing on this page can work without the camera, so say it once and
            // leave 发送 dead rather than letting it fail with a mystery later.
            showStatus(CamPlan.errorLine(DENIED_TEXT), COLOR_ERROR, TEXT_SIZE_NORMAL)
            sendButton.isEnabled = false
        }
    }

    // ---------------------------------------------------------------- camera --

    private fun startCamera() {
        if (started) return
        started = true
        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                // A surface can appear long after the owner switched the camera off
                // (a config change, the page coming back to the front); opening it
                // here would light the camera while the button says it is closed.
                if (cameraOn) openCamera(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                // The preview buffer size is fixed to the chosen camera size; the view
                // just scales it, so a rotation needs no session rebuild.
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        val existing = previewView.surfaceTexture
        if (existing != null) openCamera(existing)
    }

    /** Must be called on the UI thread: TextureView hands out its texture only there. */
    private fun openCamera(texture: SurfaceTexture) {
        if (opening || camera != null) return
        opening = true
        camHandler.post {
            val manager = getSystemService(CameraManager::class.java)
            if (manager == null) {
                opening = false
                cameraFailed(OPEN_FAILED_TEXT)
                return@post
            }
            try {
                val id = backCameraId(manager)
                if (id == null) {
                    opening = false
                    cameraFailed(NO_BACK_CAMERA_TEXT)
                    return@post
                }
                val characteristics = manager.getCameraCharacteristics(id)
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map == null) {
                    opening = false
                    cameraFailed(OPEN_FAILED_TEXT)
                    return@post
                }

                // Preview and still are picked separately and both on purpose: see
                // the class comment. CamPlan.pickSize never returns the largest size.
                val previewPicked = CamPlan.pickSize(
                    map.getOutputSizes(SurfaceTexture::class.java).map { intArrayOf(it.width, it.height) },
                    PREVIEW_TARGET_W,
                    PREVIEW_TARGET_H
                )
                val jpegPicked = CamPlan.pickSize(
                    map.getOutputSizes(ImageFormat.JPEG).map { intArrayOf(it.width, it.height) },
                    CamPlan.MAX_PX,
                    CamPlan.MAX_PX * 9 / 16
                )
                if (previewPicked == null || jpegPicked == null) {
                    opening = false
                    cameraFailed(OPEN_FAILED_TEXT)
                    return@post
                }
                val pv = Size(previewPicked[0], previewPicked[1])
                val jp = Size(jpegPicked[0], jpegPicked[1])

                // Two buffers: one frame is being encoded while the next arrives, and
                // maxImages 1 would make the second acquireLatestImage drop the first.
                val imageReader = ImageReader.newInstance(jp.width, jp.height, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ source -> onFrame(source) }, camHandler)
                reader = imageReader

                texture.setDefaultBufferSize(pv.width, pv.height)
                val surface = Surface(texture)
                previewSurface = surface

                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        opening = false
                        createSession(device, surface, imageReader)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        camera = null
                        opening = false
                        cameraFailed(DISCONNECTED_TEXT)
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        camera = null
                        opening = false
                        cameraFailed(OPEN_FAILED_TEXT + " (" + error + ")")
                    }
                }, camHandler)
            } catch (t: Throwable) {
                opening = false
                cameraFailed(describe(t))
            }
        }
    }

    // -------------------------------------------------------------- the switch --

    /**
     * 关闭摄像头 / 打开摄像头 (v0.11.4-pad12): release the camera, do not leave the page.
     *
     * The owner's correction, in his words: closing the camera means "stop capturing
     * pictures, so the next 发送 does not carry one", not "go back to the config page".
     * So this flips [cameraOn], re-labels the button and swaps the live preview for
     * [CamPlan.CAM_OFF_TEXT]; leaving the page is the system back gesture.
     *
     * It **returns at once while a send is in flight** ([busy]), yet the button stays
     * enabled: releasing the camera mid-still would pull it out from under
     * [captureStill], while a switch that looks dead for the two seconds of an upload
     * is a switch the owner presses twice. Ignoring one tap is the smaller lie.
     *
     * Runs on the UI thread (it is a click handler), so it touches views directly.
     * A camera that will not come back is reported later, from the camera thread,
     * through [cameraFailed] -- which also puts the button back to 打开摄像头 so the
     * page never claims a camera it does not have.
     */
    private fun toggleCamera() {
        if (busy) return
        if (cameraOn) {
            cameraOn = false
            applyCameraSwitch()
            // The same teardown onPause uses: one place where the session, the device,
            // the reader and the preview surface are released.
            closeCamera()
            return
        }
        val texture = previewView.surfaceTexture
        if (texture == null) {
            // Nothing to draw into, so the camera cannot come back. Say so instead of
            // flipping the label over a preview that will stay black.
            cameraFailed(OPEN_FAILED_TEXT)
            return
        }
        cameraOn = true
        applyCameraSwitch()
        openCamera(texture)
    }

    /** The one place that paints the switch; UI thread only. */
    private fun applyCameraSwitch() {
        val on = cameraOn
        cameraButton.text = if (on) CamPlan.CLOSE_CAMERA_TEXT else CamPlan.OPEN_CAMERA_TEXT
        camOffView.visibility = if (on) View.GONE else View.VISIBLE
    }

    /**
     * The camera did not come up: report it *and* leave the switch in the state that
     * is actually true (v0.11.4-pad12).
     *
     * Without the second half the button would keep reading 关闭摄像头 over a black
     * preview, i.e. the page would claim a camera that is not there. Called from the
     * camera thread, so the state change is posted to the UI thread.
     */
    private fun cameraFailed(reason: String) {
        reportError(reason)
        uiHandler.post {
            if (isFinishing || isDestroyed) return@post
            if (!cameraOn) return@post
            cameraOn = false
            applyCameraSwitch()
        }
    }

    /**
     * One session holding both surfaces: the preview Surface and the ImageReader
     * Surface. Camera2 needs both targets in the *same* session — a still capture is
     * a request on that session with the JPEG target attached, and a device forced to
     * tear the session down to switch targets loses the frame it was about to give us.
     */
    private fun createSession(
        device: CameraDevice,
        previewTarget: Surface,
        jpegReader: ImageReader
    ) {
        try {
            val targets = listOf(previewTarget, jpegReader.surface)
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(previewTarget)
                        setAutoFocus(builder)
                        configured.setRepeatingRequest(builder.build(), null, camHandler)
                        uiHandler.post {
                            if (isFinishing || isDestroyed) return@post
                            showStatus(READY_TEXT, COLOR_IDLE, TEXT_SIZE_NORMAL)
                        }
                    } catch (t: Throwable) {
                        cameraFailed(describe(t))
                    }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    cameraFailed(SESSION_FAILED_TEXT)
                }
            }, camHandler)
        } catch (t: Throwable) {
            cameraFailed(describe(t))
        }
    }

    private fun closeCamera() {
        val activeSession = session
        val activeCamera = camera
        val activeReader = reader
        val activeSurface = previewSurface
        session = null
        camera = null
        reader = null
        previewSurface = null
        opening = false
        if (!camThread.isAlive) return
        camHandler.post {
            try {
                activeSession?.close()
            } catch (t: Throwable) {
                // Already gone; nothing to report.
            }
            try {
                activeCamera?.close()
            } catch (t: Throwable) {
                // Same.
            }
            try {
                activeReader?.close()
            } catch (t: Throwable) {
                // Same.
            }
            try {
                activeSurface?.release()
            } catch (t: Throwable) {
                // Same.
            }
        }
    }

    override fun onPause() {
        // Before closeCamera(): the note says "the camera is closing", and it has
        // to leave while the fact is still true, not after the teardown.
        if (modeOpen) {
            modeOpen = false
            postMode(CamPlan.MODE_CLOSE)
        }
        closeCamera()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!modeOpen) {
            modeOpen = true
            postMode(CamPlan.MODE_OPEN)
        }
        // Back from the permission dialog or the home screen: the surface may
        // already be there, in which case no callback will fire for it.
        // The switch outranks the lifecycle (v0.11.4-pad12): onPause released the
        // camera, but if the owner closed it himself it must stay closed when the
        // page comes back -- otherwise the button says 打开摄像头 over a live preview.
        val texture = if (started && cameraOn) previewView.surfaceTexture else null
        if (texture != null && camera == null) openCamera(texture)
    }

    override fun onDestroy() {
        // Retire the ack thread first (v0.11.5-pad12): -1 means "you own nothing",
        // so a pass that is already awake writes nothing, and the interrupt cuts a
        // pass that is parked in its two-second sleep. Without both, the thread would
        // outlive the page and post a line onto a dead handler.
        ackSeq = -1L
        ackThread?.interrupt()
        ackThread = null
        uiHandler.removeCallbacksAndMessages(null)
        closeCamera()
        camThread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ send --

    /**
     * 发送：先看有没有字，再等手停下来，然后拍一帧 —— 除非相机是关着的。
     *
     * 空文字直接拒绝而不是照发一张图：操作员收到一张没有问题的照片不知道要看
     * 什么，而手机主人会以为「已经发出去了」，两个人对着同一张图猜。
     *
     * v0.11.4-pad12 起这里分成两条路，分岔点是 [cameraOn]，不是「相机开没开成」：
     * 相机被主人关掉以后，发送**不碰相机、不等 SETTLE_MS、不震**，只把这句话发出去
     * （[sendTextOnly]）。空文本两路都拦，因为那是同一句话没打。
     */
    private fun send() {
        if (busy) return
        val text = CamPlan.clampText(inputView.text.toString())
        if (text.isEmpty()) {
            showStatus(EMPTY_TEXT, COLOR_IDLE, TEXT_SIZE_NORMAL)
            return
        }
        if (!cameraOn) {
            sendTextOnly(text)
            return
        }
        if (session == null || camera == null) {
            // Not a send failure -- nothing was captured -- but the button has to be
            // usable and the reason has to be visible, so it goes out the same exit.
            reportError(NOT_READY_TEXT)
            return
        }
        busy = true
        sendButton.isEnabled = false
        // 阶段 A (v0.12.0-pad12): the sensor is about to read out, so this sentence has
        // to say "hold still" in a colour he cannot mistake for "done".
        showStatus(CamPlan.holdLine(), COLOR_HOLD, TEXT_SIZE_STRONG)

        // The hand is still moving when the button is released; a frame grabbed now
        // is a blurred picture of the ceiling. CamPlan.SETTLE_MS is that wait.
        uiHandler.postDelayed({
            if (isFinishing || isDestroyed) {
                // Leaving mid-wait used to leave 发送 disabled forever; the page may
                // still be alive (a permission dialog, a config change), so put it back.
                busy = false
                sendButton.isEnabled = true
                return@postDelayed
            }
            camHandler.post { captureStill(text) }
        }, CamPlan.SETTLE_MS)
    }

    /**
     * 只发文字 (v0.11.4-pad12): the 发送 path while the camera is released.
     *
     * No camera, no [CamPlan.SETTLE_MS], no capture buzz: there is nothing to hold
     * still and nothing to wait for, and a buzz that means "the frame is captured"
     * would be a lie. What goes out is a normal look note with an **empty blob name
     * and w/h 0** -- the relay treats a blank blob as legal and answers with a note
     * that has no picture, which is exactly what the owner asked for.
     *
     * [busy] and the disabled 发送 button are kept: two taps in flight would post the
     * same sentence twice, and the second note would look like the owner repeated
     * himself.
     */
    private fun sendTextOnly(text: String) {
        busy = true
        sendButton.isEnabled = false
        showStatus(SENDING_TEXT, COLOR_IDLE, TEXT_SIZE_NORMAL)
        // A fresh client per send, exactly like the picture path: RcClient holds no
        // state, so this needs neither the service nor a prior hello.
        val base = prefs.relayBase
        val secret = prefs.secret
        val id = prefs.agentId
        val info = agentInfo()
        Thread({
            val client = RcClient(base, secret, id, info)
            // Empty blob and zero dimensions are the agreed shape for "text only"
            // (SPEC.md §3.29); the relay stores them verbatim.
            val note = client.postNote(CamPlan.noteBody(id, text, "", 0, 0))
            if (!note.ok) {
                reportSendFailed(note.error ?: NOTE_FAILED_TEXT)
                return@Thread
            }
            val chars = text.codePointCount(0, text.length)
            // 阶段 A (v0.11.5-pad12): the send is over, so the page is unlocked and the
            // box is emptied *before* the receipt is asked for. A receipt is a fact
            // about the other side and can take half a minute; making the next send
            // wait for it would turn a courtesy into a stall.
            uiHandler.post {
                if (isFinishing || isDestroyed) return@post
                busy = false
                sendButton.isEnabled = true
                showStatus(CamPlan.uploadedTextOnlyLine(chars), COLOR_IDLE, TEXT_SIZE_NORMAL)
                inputView.setText("")
            }
            startAck(client, note.seq)
        }, "cam-text").start()
    }

    private fun captureStill(text: String) {
        val device = camera
        val activeSession = session
        val jpegReader = reader
        if (device == null || activeSession == null || jpegReader == null) {
            reportError(NOT_READY_TEXT)
            return
        }
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jpegReader.surface)
            setAutoFocus(builder)
            pendingText = text
            activeSession.capture(builder.build(), null, camHandler)
        } catch (t: Throwable) {
            pendingText = ""
            reportError(NOT_READY_TEXT)
            AgentLog.add("cam", "拍照失败: " + describe(t))
        }
    }

    /** Called on the camera thread the moment the JPEG lands in the ImageReader. */
    private fun onFrame(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            out
        } catch (t: Throwable) {
            null
        } finally {
            image.close()
        }
        if (bytes == null || bytes.isEmpty()) {
            reportSendFailed(SHOT_FAILED_TEXT)
            return
        }
        val text = pendingText
        pendingText = ""
        // 阶段 B (v0.12.0-pad12): the JPEG is in hand, so the camera work is over and the
        // owner may move. This is posted BEFORE the upload thread starts on purpose:
        // the whole point is that he learns it at the capture, not when the upload
        // ends. The buzz is the same fact for someone looking at the thing in frame
        // rather than at the screen.
        uiHandler.post {
            if (isFinishing || isDestroyed) return@post
            showStatus(CamPlan.doneHoldLine(), COLOR_DONE, TEXT_SIZE_STRONG)
            haptics.shotDone()
        }
        // Decoding, re-encoding and uploading take seconds; the camera has to keep
        // previewing through them, so they run off the camera thread.
        Thread({ deliver(bytes, text) }, "cam-send").start()
    }

    /** Decode, shrink, turn upright, re-encode, upload, report. Runs off the UI thread. */
    private fun deliver(jpeg: ByteArray, text: String) {
        val shot = try {
            shrink(jpeg)
        } catch (t: Throwable) {
            AgentLog.add("cam", "重压失败: " + describe(t))
            reportSendFailed(SHOT_FAILED_TEXT)
            return
        }
        val name = CamPlan.blobName(System.currentTimeMillis(), suffix())
        // A fresh client per send: RcClient holds no state and opens a connection per
        // call, so this needs neither the service nor a prior hello.
        val client = RcClient(prefs.relayBase, prefs.secret, prefs.agentId, agentInfo())

        val upload = client.uploadBlob(name, shot.bytes)
        if (!upload.ok) {
            reportSendFailed(upload.error ?: UPLOAD_FAILED_TEXT)
            return
        }
        val note = client.postNote(CamPlan.noteBody(prefs.agentId, text, name, shot.width, shot.height))
        if (!note.ok) {
            reportSendFailed(note.error ?: NOTE_FAILED_TEXT)
            return
        }
        // 阶段 C (v0.11.5-pad12): the upload is done, so the line goes back to the quiet
        // colour and says 已上传…等我确认. 已发送 would be the old claim, and it was
        // never the whole truth: it only ever proved the phone reached the relay.
        uiHandler.post {
            if (isFinishing || isDestroyed) return@post
            busy = false
            sendButton.isEnabled = true
            showStatus(
                CamPlan.uploadedLine(
                    shot.bytes.size,
                    shot.width,
                    shot.height,
                    text.codePointCount(0, text.length)
                ),
                COLOR_IDLE,
                TEXT_SIZE_NORMAL
            )
            inputView.setText("")
        }
        startAck(client, note.seq)
    }

    /**
     * 回执 (v0.11.5-pad12, SPEC.md section 3.29): wait for the operator to read it.
     *
     * Runs on its own thread, started only after the page is unlocked, so the receipt
     * can never delay the next send. [seq] is the relay's counter for the note that
     * was just stored; a relay that did not hand one back leaves nothing to ask
     * about, which is logged rather than guessed at.
     */
    private fun startAck(client: RcClient, seq: Long) {
        if (seq < 0L) {
            // Older relay: no seq, so no question to ask. Leave the 等我确认 line
            // standing -- it is true -- and say so in the log for the operator.
            AgentLog.add("cam", "回执查询失败: 没拿到 seq")
            return
        }
        ackSeq = seq
        val worker = Thread({ awaitRead(client, seq) }, "note-ack")
        ackThread = worker
        worker.start()
    }

    /**
     * Ask the relay about one note until it is read or the window closes.
     *
     * Every pass starts by checking [ackSeq]: if a newer send has taken the slot (or
     * the page is going away) this thread owns nothing and returns without writing.
     * A `null` answer means the question failed, which is not the same as "unread",
     * so it is retried rather than reported; only the end of the window produces
     * [CamPlan.unreadLine], and a window with no successful question at all is
     * logged as a failed query.
     */
    private fun awaitRead(client: RcClient, seq: Long) {
        var answered = false
        for (attempt in 1..ACK_ATTEMPTS) {
            try {
                Thread.sleep(ACK_INTERVAL_MS)
            } catch (t: InterruptedException) {
                return
            }
            if (ackSeq != seq) return
            val status = client.noteStatus(seq) ?: continue
            answered = true
            if (status.read) {
                uiHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (ackSeq != seq) return@post
                    showStatus(CamPlan.readLine(), COLOR_DONE, TEXT_SIZE_NORMAL)
                }
                return
            }
        }
        if (ackSeq != seq) return
        if (!answered) AgentLog.add("cam", "回执查询失败: seq " + seq)
        uiHandler.post {
            if (isFinishing || isDestroyed) return@post
            if (ackSeq != seq) return@post
            showStatus(CamPlan.unreadLine(), COLOR_IDLE, TEXT_SIZE_NORMAL)
        }
    }

    /**
     * 告诉操作员这一页开了还是关了 (v0.11.0-pad12, SPEC.md §3.31).
     *
     * Off the UI thread: the relay can be slow, and blocking in onResume or
     * onPause blocks the page. A failure only goes to the log -- this is a side
     * note about the camera, not the frame the owner pressed 发送 for, so it must
     * not put an error banner over the preview.
     */
    private fun postMode(kind: String) {
        if (!CamPlan.modeNote(kind)) return
        val base = prefs.relayBase
        val secret = prefs.secret
        val id = prefs.agentId
        val info = agentInfo()
        val body = CamPlan.modeBody(id, kind)
        Thread({
            val client = RcClient(base, secret, id, info)
            val result = client.postNote(body)
            if (!result.ok) {
                AgentLog.add("cam", "相机状态上报失败(" + kind + "): " + (result.error ?: NOTE_FAILED_TEXT))
            }
        }, "cam-mode").start()
    }

    private data class Shot(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * 重压：解码 → 按 CamPlan.MAX_PX 缩 → 转正 → 按 CamPlan.JPEG_QUALITY 编回 JPEG。
     *
     * 转正放在这里而不是交给 JPEG 的 EXIF 方向标记：BitmapFactory 不读 EXIF，重压一次
     * 之后那个标记就没了，画面会躺着送到操作员眼前。所以像素在这里转一次，出来的文件
     * 不依赖任何标记。
     */
    private fun shrink(jpeg: ByteArray): Shot {
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalStateException("decode returned null")
        val scale = CamPlan.fitScale(raw.width, raw.height, CamPlan.MAX_PX)
        val resized = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).roundToInt().coerceAtLeast(1),
                (raw.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            raw
        }
        val degrees = uprightDegrees()
        val upright = if (degrees == 0) {
            resized
        } else {
            val matrix = Matrix()
            matrix.postRotate(degrees.toFloat())
            Bitmap.createBitmap(resized, 0, 0, resized.width, resized.height, matrix, true)
        }
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, CamPlan.JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        val width = upright.width
        val height = upright.height
        if (upright !== resized) upright.recycle()
        if (resized !== raw) resized.recycle()
        raw.recycle()
        if (bytes.isEmpty()) throw IllegalStateException("compress produced nothing")
        return Shot(bytes, width, height)
    }

    /**
     * How far to turn the sensor's frame so it matches the way the phone is held.
     *
     * Sensor orientation is fixed to the hardware; the display rotation is what
     * changed. Back camera: subtract — the usual formula, and the reason a portrait
     * frame from a landscape sensor comes out sideways.
     */
    private fun uprightDegrees(): Int {
        val rotation = try {
            windowManager.defaultDisplay.rotation
        } catch (t: Throwable) {
            Surface.ROTATION_0
        }
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return ((sensorOrientation - deviceDegrees) + 360) % 360
    }

    /** Four hex nibbles for the blob name; see CamPlan.blobName. */
    private fun suffix(): String {
        val value = (System.nanoTime() xor (Math.random() * Int.MAX_VALUE).toLong()) and 0xFFFFL
        val hex = java.lang.Long.toHexString(value)
        var padded = hex
        while (padded.length < 4) padded = "0" + padded
        return padded
    }

    private fun agentInfo(): AgentInfo = AgentInfo(
        hostname = android.os.Build.DEVICE,
        user = android.os.Build.MODEL.replace(' ', '_'),
        os = "android-" + android.os.Build.VERSION.RELEASE,
        ver = BuildConfig.VERSION_NAME
    )

    // ----------------------------------------------------------------- utils --

    private fun setAutoFocus(builder: CaptureRequest.Builder) {
        try {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        } catch (t: Throwable) {
            // A fixed-focus camera has no AF mode; the frame is still usable.
        }
    }

    private fun backCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    /** One place that turns a reason into the red line, from any thread. */
    private fun reportError(reason: String) {
        reportFailure(CamPlan.errorLine(reason), reason)
    }

    /**
     * A send that did not make it (v0.12.0-pad12).
     *
     * The owner is holding a phone and looking at a room, not at a stack trace, so
     * the screen gets [CamPlan.retryLine] -- "press 发送 again" -- while the real
     * reason goes to AgentLog. The typed text is left in the box precisely so that
     * second press costs one tap and no retyping.
     */
    private fun reportSendFailed(detail: String) {
        reportFailure(CamPlan.retryLine(), detail)
    }

    /**
     * The single exit for "this did not work": red line, button back on, two buzzes.
     *
     * The buzz is here rather than at each call site so no failure path can forget
     * it: a person waiting for a picture has no other signal that it is not coming.
     */
    private fun reportFailure(line: String, detail: String) {
        AgentLog.add("cam", "失败: " + detail)
        uiHandler.post {
            if (isFinishing || isDestroyed) return@post
            busy = false
            sendButton.isEnabled = true
            showStatus(line, COLOR_ERROR, TEXT_SIZE_NORMAL)
            haptics.failed()
        }
    }

    /** One place that writes the line under the preview: text, colour and size together. */
    private fun showStatus(line: String, color: Int, sizeSp: Float) {
        statusView.text = line
        statusView.setTextColor(color)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun addButton(
        parent: LinearLayout,
        text: String,
        weight: Float,
        onClick: () -> Unit
    ): Button {
        val button = Button(this)
        button.text = text
        button.isAllCaps = false
        button.setOnClickListener { onClick() }
        parent.addView(
            button,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        )
        return button
    }

    private companion object {
        const val REQ_CAMERA = 91

        /** A 16:9 preview target; the actual size comes from the camera's own list. */
        const val PREVIEW_TARGET_W = 1280
        const val PREVIEW_TARGET_H = 720

        const val HINT_TEXT = "说一句：哪里不对 / 要我看什么"

        // 阶段 A is a warning (hold still), 阶段 B is a clearance (you may move), and
        // the two must never look alike at a glance -- that difference is the whole
        // feature. Red-orange vs green, and both slightly larger than the idle line.
        const val COLOR_HOLD = 0xFFFF6B00.toInt()
        const val COLOR_DONE = 0xFF12A150.toInt()
        const val COLOR_IDLE = 0xFF666666.toInt()
        const val COLOR_ERROR = 0xFFD32F2F.toInt()
        const val TEXT_SIZE_STRONG = 18f
        const val TEXT_SIZE_NORMAL = 14f

        /**
         * Receipt window (v0.11.5-pad12): 15 questions two seconds apart.
         *
         * Thirty seconds is the span in which the operator plausibly still has the
         * page in front of him; past that the honest answer is 还没人看 rather than a
         * thread that keeps a socket open for the rest of the session.
         */
        const val ACK_INTERVAL_MS = 2_000L
        const val ACK_ATTEMPTS = 15
        const val SEND_TEXT = "发送"

        const val READY_TEXT = "对准要我看的东西，打一句话，按发送"

        /** The only line the text-only path shows while it is in flight (v0.11.4-pad12). */
        const val SENDING_TEXT = "正在发送"
        const val EMPTY_TEXT = "先打一句话"
        const val NEED_PERMISSION_TEXT = "需要相机权限"
        const val DENIED_TEXT = "没有相机权限，去系统设置里给这个应用打开"
        const val NOT_READY_TEXT = "相机还没准备好"
        const val OPEN_FAILED_TEXT = "打不开相机"
        const val NO_BACK_CAMERA_TEXT = "这台机器没有后置相机"
        const val SESSION_FAILED_TEXT = "相机不支持同时预览和拍照"
        const val DISCONNECTED_TEXT = "相机被别的应用抢走了"
        const val SHOT_FAILED_TEXT = "这一帧没取到"
        const val UPLOAD_FAILED_TEXT = "图没传上去"
        const val NOTE_FAILED_TEXT = "文字没传上去"
    }

    /**
     * 手上能感觉到的那一下 (v0.12.0-pad12).
     *
     * The owner is pointing the camera at something and looking at *that*, not at
     * the line under the preview, so "拍好了" has to arrive through the hand as well
     * as through the screen: one short buzz when the frame is captured, two when the
     * send failed.
     *
     * VIBRATE is a normal permission (AndroidManifest.xml already declares it), so
     * there is no runtime request and no way for this to be denied. minSdk is 31, so
     * VibrationEffect is always available and needs no version branch.
     *
     * Every call swallows its Throwable: a device with the motor off, or a vibrator
     * taken away by another app, must not turn a captured frame into a failed send.
     */
    private class Haptics(context: Context) {

        // VibratorManager rather than the deprecated Context.VIBRATOR_SERVICE string:
        // minSdk is 31, so the manager is always there, and it is the only form the
        // current SDK does not flag.
        private val vibrator: Vibrator? = try {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } catch (t: Throwable) {
            AgentLog.add("cam", "震动服务不可用: " + describe(t))
            null
        }

        /** One 70 ms buzz: the frame is captured, the owner may move. */
        fun shotDone() {
            fire(VibrationEffect.createOneShot(SHOT_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        /** Two short buzzes with a gap: the send failed, press 发送 again. */
        fun failed() {
            fire(VibrationEffect.createWaveform(longArrayOf(0L, FAIL_MS, GAP_MS, FAIL_MS), -1))
        }

        private fun fire(effect: VibrationEffect) {
            val target = vibrator ?: return
            try {
                target.vibrate(effect)
            } catch (t: Throwable) {
                AgentLog.add("cam", "震动失败: " + describe(t))
            }
        }

        private companion object {
            /** Short enough not to be annoying, long enough to feel through a case. */
            const val SHOT_MS = 70L
            const val FAIL_MS = 60L
            const val GAP_MS = 90L
        }
    }
}
