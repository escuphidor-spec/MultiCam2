package com.example.multicam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.multicam.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.random.Random

data class CameraSlotConfig(
    var name: String,
    var rtspUrl: String,
    var onvifPort: Int = 8899,
    var isMuted: Boolean = false,
    var invertPan: Boolean = false,
    var invertTilt: Boolean = false
)

data class DualTabPage(
    var title: String,
    var topCamera: CameraSlotConfig? = null,
    var bottomCamera: CameraSlotConfig? = null
)

object AppPrefs {
    private const val PREFS_NAME = "multicam_dual_prefs"
    private const val KEY_TABS = "tabs_array_v2"

    fun loadTabs(context: Context): MutableList<DualTabPage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_TABS, null)
        if (json.isNullOrEmpty()) {
            val initial = mutableListOf(DualTabPage("Pestaña 1"))
            saveTabs(context, initial)
            return initial
        }
        // Usamos Array nativo para evitar errores de TypeToken en Kotlin
        val array = Gson().fromJson(json, Array<DualTabPage>::class.java) ?: emptyArray()
        return if (array.isEmpty()) mutableListOf(DualTabPage("Pestaña 1")) else array.toMutableList()
    }

    fun saveTabs(context: Context, tabs: List<DualTabPage>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = tabs.toTypedArray()
        prefs.edit().putString(KEY_TABS, Gson().toJson(array)).apply()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var libVLC: LibVLC
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ptzExecutor = Executors.newCachedThreadPool()
    private val profileTokensCache = mutableMapOf<String, List<String>>()

    private var tabs = mutableListOf<DualTabPage>()
    private var currentTabIndex = 0

    private var topPlayer: MediaPlayer? = null
    private var bottomPlayer: MediaPlayer? = null

    private var topScale = 1f
    private var bottomScale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libVLC = LibVLC(
            applicationContext,
            arrayListOf("--network-caching=400", "--rtsp-tcp")
        )

        tabs = AppPrefs.loadTabs(this)
        setupTabsUI()
        setupSlotListeners()
    }

    private fun setupTabsUI() {
        binding.tabLayout.removeAllTabs()
        tabs.forEachIndexed { index, page ->
            val tab = binding.tabLayout.newTab().setText(page.title)
            binding.tabLayout.addTab(tab, index == currentTabIndex)

            // Mantener presionada una pestaña permite renombrarla o eliminarla
            tab.view.setOnLongClickListener {
                showTabOptionsDialog(index)
                true
            }
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newIndex = tab?.position ?: 0
                if (newIndex != currentTabIndex && newIndex in tabs.indices) {
                    currentTabIndex = newIndex
                    loadActiveTabCameras()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnAddTab.setOnClickListener {
            val newTabNumber = tabs.size + 1
            tabs.add(DualTabPage("Pestaña $newTabNumber"))
            AppPrefs.saveTabs(this, tabs)
            currentTabIndex = tabs.lastIndex
            setupTabsUI()
            loadActiveTabCameras()
        }
    }

    private fun showTabOptionsDialog(tabIndex: Int) {
        if (tabIndex !in tabs.indices) return
        val page = tabs[tabIndex]
        val options = if (tabs.size > 1) {
            arrayOf("Renombrar ${page.title}", "Eliminar ${page.title}")
        } else {
            arrayOf("Renombrar ${page.title}")
        }

        AlertDialog.Builder(this)
            .setTitle(page.title)
            .setItems(options) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply {
                        setText(page.title)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Renombrar pestaña")
                        .setView(input)
                        .setPositiveButton("Guardar") { _, _ ->
                            val newName = input.text.toString().trim()
                            if (newName.isNotEmpty()) {
                                page.title = newName
                                AppPrefs.saveTabs(this, tabs)
                                setupTabsUI()
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else if (which == 1 && tabs.size > 1) {
                    stopAllPlayers()
                    tabs.removeAt(tabIndex)
                    if (currentTabIndex >= tabs.size) {
                        currentTabIndex = tabs.lastIndex
                    }
                    AppPrefs.saveTabs(this, tabs)
                    setupTabsUI()
                    loadActiveTabCameras()
                }
            }
            .show()
    }

    private fun setupSlotListeners() {
        // Botones para agregar o editar Cámara Superior (Mitad 1)
        binding.btnAddTop.setOnClickListener {
            showCameraConfigDialog(isTopSlot = true)
        }
        binding.btnEditTop.setOnClickListener {
            showCameraConfigDialog(isTopSlot = true)
        }
        binding.btnResetZoomTop.setOnClickListener {
            topScale = 1f
            binding.textureTop.scaleX = 1f
            binding.textureTop.scaleY = 1f
        }
        binding.btnMuteTop.setOnClickListener {
            val cam = tabs.getOrNull(currentTabIndex)?.topCamera ?: return@setOnClickListener
            cam.isMuted = !cam.isMuted
            topPlayer?.volume = if (cam.isMuted) 0 else 100
            updateMuteIcon(binding.btnMuteTop, cam.isMuted)
            AppPrefs.saveTabs(this, tabs)
        }

        // Botones para agregar o editar Cámara Inferior (Mitad 2)
        binding.btnAddBottom.setOnClickListener {
            showCameraConfigDialog(isTopSlot = false)
        }
        binding.btnEditBottom.setOnClickListener {
            showCameraConfigDialog(isTopSlot = false)
        }
        binding.btnResetZoomBottom.setOnClickListener {
            bottomScale = 1f
            binding.textureBottom.scaleX = 1f
            binding.textureBottom.scaleY = 1f
        }
        binding.btnMuteBottom.setOnClickListener {
            val cam = tabs.getOrNull(currentTabIndex)?.bottomCamera ?: return@setOnClickListener
            cam.isMuted = !cam.isMuted
            bottomPlayer?.volume = if (cam.isMuted) 0 else 100
            updateMuteIcon(binding.btnMuteBottom, cam.isMuted)
            AppPrefs.saveTabs(this, tabs)
        }

        setupTouchGestures(
            container = binding.slotTopContainer,
            textureView = binding.textureTop,
            isTopSlot = true
        )

        setupTouchGestures(
            container = binding.slotBottomContainer,
            textureView = binding.textureBottom,
            isTopSlot = false
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures(
        container: FrameLayout,
        textureView: TextureView,
        isTopSlot: Boolean
    ) {
        val swipeThreshold = 35f * resources.displayMetrics.density

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (isTopSlot) {
                        topScale = (topScale * detector.scaleFactor).coerceIn(1f, 4f)
                        textureView.scaleX = topScale
                        textureView.scaleY = topScale
                    } else {
                        bottomScale = (bottomScale * detector.scaleFactor).coerceIn(1f, 4f)
                        textureView.scaleX = bottomScale
                        textureView.scaleY = bottomScale
                    }
                    return true
                }
            }
        )

        var downX = 0f
        var downY = 0f
        var wasScaling = false

        container.setOnTouchListener { _, event ->
            val currentCam = if (isTopSlot) {
                tabs.getOrNull(currentTabIndex)?.topCamera
            } else {
                tabs.getOrNull(currentTabIndex)?.bottomCamera
            } ?: return@setOnTouchListener false

            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress || event.pointerCount > 1) {
                wasScaling = true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    wasScaling = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!wasScaling) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) > swipeThreshold || abs(dy) > swipeThreshold) {
                            if (abs(dx) > abs(dy)) {
                                var pan = if (dx > 0) 0.6f else -0.6f
                                if (currentCam.invertPan) pan = -pan
                                moveCameraPtz(currentCam, pan, 0f)
                            } else {
                                var tilt = if (dy < 0) 0.6f else -0.6f
                                if (currentCam.invertTilt) tilt = -tilt
                                moveCameraPtz(currentCam, 0f, tilt)
                            }
                        }
                    }
                    wasScaling = false
                    true
                }
                else -> true
            }
        }
    }

    private fun showCameraConfigDialog(isTopSlot: Boolean) {
        val currentPage = tabs.getOrNull(currentTabIndex) ?: return
        val existing = if (isTopSlot) currentPage.topCamera else currentPage.bottomCamera

        val defaultPort = if (isTopSlot) 554 else 1554
        val defaultOnvif = if (isTopSlot) 8899 else 18899
        val defaultName = if (isTopSlot) {
            "Cámara ${currentTabIndex * 2 + 1}"
        } else {
            "Cámara ${currentTabIndex * 2 + 2}"
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val tvNameLabel = TextView(this).apply { text = "Nombre de la cámara:" }
        val etName = EditText(this).apply {
            setText(existing?.name ?: defaultName)
        }

        val tvUrlLabel = TextView(this).apply {
            text = "Dirección RTSP (Wi-Fi o DDNS Datos Móviles):"
            setPadding(0, 20, 0, 0)
        }
        val etUrl = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(
                existing?.rtspUrl
                    ?: "rtsp://admin:Camara2026@micamara2026.ddns.net:$defaultPort/live/ch00_0"
            )
        }

        val tvOnvifLabel = TextView(this).apply {
            text = "Puerto ONVIF de movimiento (Ej. 8899 para Cám 1, 18899 para Cám 2):"
            setPadding(0, 20, 0, 0)
        }
        val etOnvifPort = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.onvifPort ?: defaultOnvif).toString())
        }

        // Si el usuario cambia el puerto en la URL (ej. :1554), sugerir el puerto ONVIF automáticamente
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val parsed = Uri.parse(s?.toString() ?: "")
                val rtspPort = parsed.port
                if (rtspPort > 0) {
                    val suggestedOnvif = if (rtspPort > 1000) (rtspPort - 554) + 8899 else 8899
                    etOnvifPort.setText(suggestedOnvif.toString())
                }
            }
        })

        val cbInvertPan = CheckBox(this).apply {
            text = "Invertir giro Izquierda / Derecha"
            isChecked = existing?.invertPan ?: false
        }

        val cbInvertTilt = CheckBox(this).apply {
            text = "Invertir giro Arriba / Abajo"
            isChecked = existing?.invertTilt ?: false
        }

        layout.addView(tvNameLabel)
        layout.addView(etName)
        layout.addView(tvUrlLabel)
        layout.addView(etUrl)
        layout.addView(tvOnvifLabel)
        layout.addView(etOnvifPort)
        layout.addView(cbInvertPan)
        layout.addView(cbInvertTilt)

        val scrollView = ScrollView(this).apply { addView(layout) }

        val dialogBuilder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Añadir Cámara" else "Configurar Cámara")
            .setView(scrollView)
            .setPositiveButton("Guardar") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { defaultName }
                val url = etUrl.text.toString().trim()
                val onvifPort = etOnvifPort.text.toString().trim().toIntOrNull() ?: defaultOnvif

                if (url.isNotEmpty()) {
                    val newConfig = CameraSlotConfig(
                        name = name,
                        rtspUrl = url,
                        onvifPort = onvifPort,
                        isMuted = existing?.isMuted ?: false,
                        invertPan = cbInvertPan.isChecked,
                        invertTilt = cbInvertTilt.isChecked
                    )
                    if (isTopSlot) {
                        currentPage.topCamera = newConfig
                    } else {
                        currentPage.bottomCamera = newConfig
                    }
                    AppPrefs.saveTabs(this, tabs)
                    loadActiveTabCameras()
                }
            }
            .setNegativeButton("Cancelar", null)

        if (existing != null) {
            dialogBuilder.setNeutralButton("Quitar Cámara") { _, _ ->
                if (isTopSlot) {
                    currentPage.topCamera = null
                } else {
                    currentPage.bottomCamera = null
                }
                AppPrefs.saveTabs(this, tabs)
                loadActiveTabCameras()
            }
        }

        dialogBuilder.show()
    }

    override fun onResume() {
        super.onResume()
        loadActiveTabCameras()
    }

    override fun onPause() {
        super.onPause()
        stopAllPlayers()
    }

    private fun loadActiveTabCameras() {
        stopAllPlayers()
        val page = tabs.getOrNull(currentTabIndex) ?: return

        // Configurar Mitad Superior
        val topCam = page.topCamera
        if (topCam == null) {
            binding.btnAddTop.visibility = View.VISIBLE
            binding.textureTop.visibility = View.GONE
            binding.headerTop.visibility = View.GONE
            binding.tvStatusTop.visibility = View.GONE
        } else {
            binding.btnAddTop.visibility = View.GONE
            binding.textureTop.visibility = View.VISIBLE
            binding.headerTop.visibility = View.VISIBLE
            binding.tvNameTop.text = topCam.name
            updateMuteIcon(binding.btnMuteTop, topCam.isMuted)
            topScale = 1f
            binding.textureTop.scaleX = 1f
            binding.textureTop.scaleY = 1f
            startSlotStream(
                config = topCam,
                textureView = binding.textureTop,
                tvStatus = binding.tvStatusTop,
                isTopSlot = true
            )
        }

        // Configurar Mitad Inferior
        val bottomCam = page.bottomCamera
        if (bottomCam == null) {
            binding.btnAddBottom.visibility = View.VISIBLE
            binding.textureBottom.visibility = View.GONE
            binding.headerBottom.visibility = View.GONE
            binding.tvStatusBottom.visibility = View.GONE
        } else {
            binding.btnAddBottom.visibility = View.GONE
            binding.textureBottom.visibility = View.VISIBLE
            binding.headerBottom.visibility = View.VISIBLE
            binding.tvNameBottom.text = bottomCam.name
            updateMuteIcon(binding.btnMuteBottom, bottomCam.isMuted)
            bottomScale = 1f
            binding.textureBottom.scaleX = 1f
            binding.textureBottom.scaleY = 1f
            startSlotStream(
                config = bottomCam,
                textureView = binding.textureBottom,
                tvStatus = binding.tvStatusBottom,
                isTopSlot = false
            )
        }
    }

    private fun updateMuteIcon(button: ImageButton, isMuted: Boolean) {
        val icon = if (isMuted) {
            android.R.drawable.ic_lock_silent_mode
        } else {
            android.R.drawable.ic_lock_silent_mode_off
        }
        button.setImageResource(icon)
    }

    private fun startSlotStream(
        config: CameraSlotConfig,
        textureView: TextureView,
        tvStatus: TextView,
        isTopSlot: Boolean
    ) {
        tvStatus.text = "Conectando..."
        tvStatus.visibility = View.VISIBLE

        val mediaPlayer = MediaPlayer(libVLC)
        if (isTopSlot) {
            topPlayer = mediaPlayer
        } else {
            bottomPlayer = mediaPlayer
        }

        val attachAndPlay = { width: Int, height: Int ->
            val vout = mediaPlayer.vlcVout
            vout.setVideoView(textureView)
            if (width > 0 && height > 0) {
                vout.setWindowSize(width, height)
            }
            vout.attachViews()

            val media = Media(libVLC, Uri.parse(config.rtspUrl)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=400")
                addOption(":rtsp-tcp")
            }
            mediaPlayer.media = media
            media.release()

            mediaPlayer.setEventListener { event ->
                mainHandler.post {
                    when (event.type) {
                        MediaPlayer.Event.Playing,
                        MediaPlayer.Event.Vout -> {
                            tvStatus.visibility = View.GONE
                            mediaPlayer.volume = if (config.isMuted) 0 else 100
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            tvStatus.text = "Error RTSP (Toque engrane para revisar URL)"
                            tvStatus.visibility = View.VISIBLE
                        }
                    }
                }
            }
            mediaPlayer.play()
        }

        if (textureView.isAvailable) {
            attachAndPlay(textureView.width, textureView.height)
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (!mediaPlayer.vlcVout.areViewsAttached()) {
                    attachAndPlay(width, height)
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                mediaPlayer.vlcVout.setWindowSize(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun stopAllPlayers() {
        topPlayer?.let {
            it.stop()
            it.vlcVout.detachViews()
            it.release()
        }
        topPlayer = null

        bottomPlayer?.let {
            it.stop()
            it.vlcVout.detachViews()
            it.release()
        }
        bottomPlayer = null
    }

    // Controlador ONVIF PTZ mejorado para soportar Cámara 1, Cámara 2 y múltiples tokens V380/ONVIF
    private fun moveCameraPtz(config: CameraSlotConfig, panX: Float, tiltY: Float) {
        ptzExecutor.execute {
            try {
                val uri = Uri.parse(config.rtspUrl)
                val host = uri.host ?: return@execute
                val onvifPort = config.onvifPort

                val userInfo = uri.userInfo ?: ""
                val user = userInfo.substringBefore(":", "admin")
                val pass = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""

                val cacheKey = "$host:$onvifPort"
                val tokens = profileTokensCache[cacheKey] ?: fetchProfileTokens(host, onvifPort, user, pass).also {
                    profileTokensCache[cacheKey] = it
                }

                // Enviar comando al token principal detectado en esa cámara específica
                var moved = false
                for (token in tokens) {
                    val moveBody = """
                        <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                            <tptz:ProfileToken>$token</tptz:ProfileToken>
                            <tptz:Velocity>
                                <tt:PanTilt x="$panX" y="$tiltY"/>
                            </tptz:Velocity>
                        </tptz:ContinuousMove>
                    """.trimIndent()

                    val stopBody = """
                        <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                            <tptz:ProfileToken>$token</tptz:ProfileToken>
                            <tptz:PanTilt>true</tptz:PanTilt>
                            <tptz:Zoom>true</tptz:Zoom>
                        </tptz:Stop>
                    """.trimIndent()

                    val res = sendOnvifSoap(host, onvifPort, "/onvif/ptz_service", user, pass, moveBody)
                    if (res != null) {
                        moved = true
                        Thread.sleep(450)
                        sendOnvifSoap(host, onvifPort, "/onvif/ptz_service", user, pass, stopBody)
                        break
                    }
                }

                if (!moved) {
                    mainHandler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "Sin respuesta PTZ en $host:$onvifPort (Verifica el puerto ONVIF)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchProfileTokens(host: String, onvifPort: Int, user: String, pass: String): List<String> {
        val body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        val response = sendOnvifSoap(host, onvifPort, "/onvif/media_service", user, pass, body) ?: ""
        val found = Regex("""Profiles[^>]*token="([^"]+)"""").findAll(response)
            .map { it.groupValues[1] }
            .toList()

        return if (found.isNotEmpty()) {
            found
        } else {
            // Tokens universales de respaldo para cámaras V380 / ONVIF
            listOf("Profile_1", "Profile_0", "IPCProfilesToken0", "IPCProfilesToken1", "000")
        }
    }

    private fun sendOnvifSoap(
        host: String,
        port: Int,
        path: String,
        user: String,
        pass: String,
        bodyXml: String
    ): String? {
        val endpoints = listOf(path, "/onvif/ptz", "/onvif/device_service")
        // Intentar primero con cabecera WS-Security y luego sin ella (por si el reloj interno de la Cámara 2 está desfasado)
        val headersToTry = if (user.isNotEmpty()) {
            listOf(buildWsSecurityHeader(user, pass), "")
        } else {
            listOf("")
        }

        for (securityHeader in headersToTry) {
            val envelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
                    $securityHeader
                    <s:Body>$bodyXml</s:Body>
                </s:Envelope>
            """.trimIndent()

            for (ep in endpoints) {
                try {
                    val url = URL("http://$host:$port$ep")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 2000
                        readTimeout = 2000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
                    }
                    conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode in 200..299) {
                        return conn.inputStream.bufferedReader().use { it.readText() }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun buildWsSecurityHeader(user: String, pass: String): String {
        val nonceBytes = ByteArray(16).also { Random.nextBytes(it) }
        val nonceBase64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = sdf.format(Date())

        val md = MessageDigest.getInstance("SHA-1")
        md.update(nonceBytes)
        md.update(created.toByteArray(Charsets.UTF_8))
        md.update(pass.toByteArray(Charsets.UTF_8))
        val digestBase64 = Base64.encodeToString(md.digest(), Base64.NO_WRAP)

        return """
            <s:Header>
                <Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                    <UsernameToken>
                        <Username>$user</Username>
                        <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestBase64</Password>
                        <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</Nonce>
                        <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                    </UsernameToken>
                </Security>
            </s:Header>
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllPlayers()
        ptzExecutor.shutdownNow()
        if (::libVLC.isInitialized) {
            libVLC.release()
        }
    }
}
