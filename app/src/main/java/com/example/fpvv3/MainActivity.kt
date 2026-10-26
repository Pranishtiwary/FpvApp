/*
 * OFFLINE FPV LINK : everything in one file (MainActivity.kt)
 * Wi-Fi Direct + in-app TCP signaling + WebRTC video/audio + Compose UI.
 *
 * ============================ SETUP (read first) ============================
 * NOT COMPILED OR DEVICE-TESTED when generated. Verify dependency versions in Android Studio.
 *
 * app/build.gradle.kts (minSdk 26, compileSdk/targetSdk 34 or 35, Compose compiler plugin enabled):
 *   implementation("io.github.webrtc-sdk:android:125.6422.07")   // maintained WebRTC fork, package org.webrtc. VERIFY version on Maven Central
 *   implementation(platform("androidx.compose:compose-bom:2024.09.00"))
 *   implementation("androidx.compose.ui:ui")
 *   implementation("androidx.compose.material3:material3")
 *   implementation("androidx.activity:activity-compose:1.9.2")
 *   implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
 *   implementation("androidx.datastore:datastore-preferences:1.1.1")
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 *   implementation("androidx.core:core-ktx:1.13.1")
 *
 * AndroidManifest.xml (inside <manifest>):
 *   <uses-feature android:name="android.hardware.wifi.direct" android:required="true"/>
 *   <uses-feature android:name="android.hardware.camera" android:required="false"/>
 *   <uses-permission android:name="android.permission.INTERNET"/>   <!-- needed for local sockets, NOT for internet use -->
 *   <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
 *   <uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
 *   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
 *   <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation"/>
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32"/>
 *   <uses-permission android:name="android.permission.CAMERA"/>
 *   <uses-permission android:name="android.permission.RECORD_AUDIO"/>
 *   <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA"/>
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
 * Inside <application>:
 *   <activity android:name=".MainActivity" android:exported="true"
 *       android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
 *       <intent-filter><action android:name="android.intent.action.MAIN"/>
 *       <category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
 *   <service android:name=".StreamService" android:exported="false"
 *       android:foregroundServiceType="camera|microphone"/>
 *
 * Package name below is com.offlinefpv.link : change it to match your project.
 * ============================================================================
 */
package com.example.fpvv3

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.webrtc.*
import java.io.BufferedWriter
import java.io.IOException
import java.io.Reader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

// ───────────────────────────── MODELS ─────────────────────────────

enum class Role(val code: String) { TRANSMITTER("TX"), VIEWER("VIEWER") }
enum class Mode(val label: String) { DIRECT("Offline Direct"), LOCAL("Local Wi-Fi") }
enum class Screen { HOME, DISCOVERY, PROGRESS, TX, VIEWER, SETTINGS, DIAG }
enum class Quality(val label: String, val w: Int, val h: Int, val fps: Int, val bitrate: Int) {
    LOW("Low Latency", 640, 480, 30, 800_000),
    BALANCED("Balanced", 1280, 720, 30, 2_000_000),
    HIGH("High Quality", 1920, 1080, 30, 4_000_000)
}

data class Peer(val name: String, val address: String, val status: Int)

data class AppSettings(
    val quality: Quality = Quality.LOW,
    val startMuted: Boolean = false,
    val showStats: Boolean = true,
    val port: Int = 8988,
    val frontCamera: Boolean = false,
    val localHost: String = "",
    val verboseLog: Boolean = true,
    val disableNetMonitor: Boolean = false // A/B test flag; applied when the WebRTC factory is first created
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val role: Role? = null,
    val mode: Mode = Mode.DIRECT,
    val p2pEnabled: Boolean? = null,
    val discovering: Boolean = false,
    val peers: List<Peer> = emptyList(),
    val selected: Peer? = null,
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val ownerIp: String? = null,
    val localIps: List<String> = emptyList(),
    val sigState: String = "idle",
    val handshake: Boolean = false,
    val progress: List<String> = emptyList(),
    val error: String? = null,
    val connLost: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val quality: Quality = Quality.LOW,
    val front: Boolean = false,
    val startedAt: Long = 0,
    val retries: Int = 0,
    val logs: List<String> = emptyList(),
    val settings: AppSettings = AppSettings()
)

data class RtcState(
    val signaling: String = "-", val gathering: String = "-", val ice: String = "-", val pc: String = "-",
    val camera: String = "off", val mic: String = "off",
    val remoteVideo: Boolean = false, val remoteAudio: Boolean = false, val firstFrame: Boolean = false,
    val stats: String = ""
)

// ───────────────────────── SIGNALING PROTOCOL ─────────────────────────

data class Msg(
    val type: String, val role: String? = null, val sdp: String? = null,
    val mid: String? = null, val idx: Int = 0, val cand: String? = null, val text: String? = null
)

object SignalCodec {
    const val VERSION = 1
    const val MAX_LEN = 65_536
    val TYPES = setOf("join", "accept", "reject", "offer", "answer", "ice", "bye", "ping", "error")

    fun encode(m: Msg): String {
        val o = JSONObject().put("v", VERSION).put("t", m.type)
        m.role?.let { o.put("role", it) }; m.sdp?.let { o.put("sdp", it) }
        m.mid?.let { o.put("mid", it) }; m.cand?.let { o.put("cand", it); o.put("idx", m.idx) }
        m.text?.let { o.put("text", it.take(200)) }
        return o.toString()
    }

    /** Returns null for anything malformed, oversized, wrong version or semantically invalid. */
    fun decode(s: String): Msg? {
        if (s.length > MAX_LEN) return null
        return try {
            val o = JSONObject(s)
            if (o.optInt("v", -1) != VERSION) return null
            val t = o.optString("t", "")
            if (t !in TYPES) return null
            fun str(k: String): String? = if (o.has(k)) o.getString(k) else null
            val m = Msg(t, str("role"), str("sdp"), str("mid"), o.optInt("idx", 0), str("cand"), str("text"))
            if (validate(m)) m else null
        } catch (e: Exception) { null }
    }

    fun validate(m: Msg): Boolean = when (m.type) {
        "join", "accept" -> m.role == "TX" || m.role == "VIEWER"
        "offer", "answer" -> !m.sdp.isNullOrEmpty() && m.sdp.length <= 60_000
        "ice" -> !m.cand.isNullOrEmpty() && m.cand.length <= 2_000 && (m.mid?.length ?: 0) <= 100 && m.idx in 0..16
        else -> true
    }
}

// ───────────────────── LOCAL TCP SIGNALING (no external server) ─────────────────────
// Newline-delimited JSON over a plain TCP socket. The Wi-Fi Direct group owner (or the
// transmitter in Local Wi-Fi mode) listens; exactly one peer may be connected at a time.

class LocalSignaling(
    private val scope: CoroutineScope,
    private val onMsg: (Msg) -> Unit,
    private val onState: (String) -> Unit,
    private val onPeerClosed: () -> Unit
) {
    private var server: ServerSocket? = null
    @Volatile private var sock: Socket? = null
    @Volatile private var outCh: Channel<String>? = null
    private val jobs = CopyOnWriteArrayList<Job>()

    fun host(port: Int) {
        jobs += scope.launch(Dispatchers.IO) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } // all interfaces
                server = ss
                if (!isActive) return@launch // cancelled during bind: finally{} closes the socket
                onState("listening on :$port")
                while (isActive) {
                    val s = ss.accept() // unblocked by ss.close() in close()
                    if (!isActive) { s.close(); break }
                    val busy = sock?.let { it.isConnected && !it.isClosed } == true
                    val a = s.inetAddress
                    // Only local peers; only one at a time.
                    if (busy || !(a.isSiteLocalAddress || a.isLinkLocalAddress)) { s.close(); continue }
                    attach(s)
                }
            } catch (e: Exception) { if (isActive) onState("server error: ${e.message}") }
            finally { runCatching { ss?.close() } }
        }
    }

    fun join(ip: String, port: Int, attempts: Int = 10) {
        jobs += scope.launch(Dispatchers.IO) {
            repeat(attempts) { i ->
                if (!isActive) return@launch
                try {
                    onState("connecting to $ip:$port (try ${i + 1}/$attempts)")
                    val s = Socket(); s.connect(InetSocketAddress(ip, port), 3000)
                    attach(s); return@launch
                } catch (e: Exception) { delay(1000) }
            }
            onState("host unreachable: $ip:$port")
        }
    }

    private fun attach(s: Socket) {
        s.tcpNoDelay = true; s.soTimeout = 20_000
        sock = s
        val ch = Channel<String>(Channel.UNLIMITED); outCh = ch
        onState("connected")
        jobs += scope.launch(Dispatchers.IO) { // writer (single coroutine = ordered)
            try {
                val w: BufferedWriter = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                for (line in ch) { w.write(line); w.write("\n"); w.flush() }
            } catch (_: Exception) { }
        }
        jobs += scope.launch(Dispatchers.IO) { // reader
            var bad = 0
            try {
                val r = s.getInputStream().bufferedReader(Charsets.UTF_8)
                while (isActive) {
                    val line = readBounded(r) ?: break
                    val m = SignalCodec.decode(line)
                    if (m == null) { if (++bad > 5) break else continue }
                    onMsg(m)
                }
            } catch (_: Exception) { }
            runCatching { s.close() }
            ch.close()
            if (sock === s) { sock = null; outCh = null; onPeerClosed() }
        }
    }

    private fun readBounded(r: Reader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (sb.length >= SignalCodec.MAX_LEN) throw IOException("line too long")
            sb.append(c.toChar())
        }
    }

    fun send(m: Msg) { outCh?.trySend(SignalCodec.encode(m)) }

    fun close() {
        // Clear references FIRST: the reader's `sock === s` check then fails, so an
        // intentional close never fires onPeerClosed.
        val s = sock; val ss = server; val ch = outCh
        sock = null; server = null; outCh = null
        jobs.forEach { it.cancel() }; jobs.clear()
        ch?.close()
        runCatching { s?.close() }; runCatching { ss?.close() } // ss.close() unblocks accept()
    }
}

// ──────────────────────────── WEBRTC MANAGER ────────────────────────────

private open class SdpObs : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

private open class PcObs : PeerConnection.Observer {
    override fun onSignalingChange(s: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(c: IceCandidate) {}
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
    override fun onAddStream(s: MediaStream) {}
    override fun onRemoveStream(s: MediaStream) {}
    override fun onDataChannel(d: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
    override fun onTrack(t: RtpTransceiver) {}
    override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {}
}

class WebRtcManager(
    private val ctx: Context,
    private val onIce: (IceCandidate) -> Unit,
    private val log: (String) -> Unit,
    private val disableNetMonitor: () -> Boolean
) {
    val egl: EglBase by lazy { EglBase.create() }
    val state = MutableStateFlow(RtcState())

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var quality = Quality.LOW
    private var remoteTrack: VideoTrack? = null
    private var remoteSink: VideoSink? = null
    private var localSink: VideoSink? = null
    private val pending = Collections.synchronizedList(mutableListOf<IceCandidate>())
    @Volatile private var remoteSet = false
    private var lastBytes = 0.0; private var lastT = 0L
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun upd(f: (RtcState) -> RtcState) = state.update(f)

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        val adm = org.webrtc.audio.JavaAudioDeviceModule.builder(ctx)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            // HYPOTHESIS, NOT CONFIRMED: Wi-Fi Direct interfaces may be skipped by WebRTC's Android network
            // monitor. The field exists in upstream org.webrtc; if it fails to compile in your artifact, delete
            // this line. Controlled by a Settings toggle for A/B testing (takes effect on next app launch).
            .setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = disableNetMonitor() })
            .createPeerConnectionFactory()
        log("WebRTC factory created, disableNetworkMonitor=${disableNetMonitor()}")
        factory = f; return f
    }

    /** Creates the single peer connection and local tracks. Safe against duplicate calls. */
    fun start(transmitter: Boolean, q: Quality, front: Boolean, muted: Boolean, micAllowed: Boolean, camAllowed: Boolean) {
        if (pc != null) return
        quality = q; remoteSet = false; pending.clear()
        val f = ensureFactory()
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION") am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        am.isSpeakerphoneOn = true

        // Offline: NO ICE servers. Host candidates only → no STUN/TURN lookups, no internet.
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        val p = f.createPeerConnection(cfg, object : PcObs() {
            override fun onIceCandidate(c: IceCandidate) = onIce(c)
            override fun onSignalingChange(s: PeerConnection.SignalingState) { upd { it.copy(signaling = s.name) } }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { upd { it.copy(gathering = s.name) } }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { upd { it.copy(ice = s.name) }; log("ICE: $s") }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { upd { it.copy(pc = s.name) }; log("PC: $s") }
            override fun onTrack(t: RtpTransceiver) {
                val tr = t.receiver.track() ?: return
                if (tr.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val v = tr as VideoTrack; v.setEnabled(true); remoteTrack = v
                    remoteSink?.let { v.addSink(it) }
                    upd { it.copy(remoteVideo = true) }
                } else if (tr.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
                    tr.setEnabled(true); upd { it.copy(remoteAudio = true) }
                }
            }
        }) ?: run { log("createPeerConnection returned null"); return }
        pc = p

        if (micAllowed) {
            val c = MediaConstraints().apply {
                optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = f.createAudioSource(c)
            audioTrack = f.createAudioTrack("a0", audioSource).also {
                it.setEnabled(!muted); p.addTrack(it, listOf("s0"))
            }
            upd { it.copy(mic = if (muted) "muted" else "live") }
        } else upd { it.copy(mic = "no permission") }

        if (transmitter) {
            if (!camAllowed) { upd { it.copy(camera = "no permission") }; return }
            startCamera(f, p, front)
        }
    }

    private fun startCamera(f: PeerConnectionFactory, p: PeerConnection, front: Boolean) {
        val en = Camera2Enumerator(ctx)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) == front } ?: en.deviceNames.firstOrNull()
        if (name == null) { upd { it.copy(camera = "no camera") }; return }
        val cap = en.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(e: String?) { upd { it.copy(camera = "error: $e") } }
            override fun onCameraDisconnected() { upd { it.copy(camera = "disconnected") } }
            override fun onCameraFreezed(e: String?) { upd { it.copy(camera = "frozen") } }
            override fun onCameraOpening(n: String?) { upd { it.copy(camera = "opening") } }
            override fun onFirstFrameAvailable() { upd { it.copy(camera = "live") } }
            override fun onCameraClosed() {}
        })
        if (cap == null) { upd { it.copy(camera = "open failed") }; return }
        capturer = cap
        helper = SurfaceTextureHelper.create("capture", egl.eglBaseContext)
        videoSource = f.createVideoSource(cap.isScreencast)
        cap.initialize(helper, ctx, videoSource!!.capturerObserver)
        cap.startCapture(quality.w, quality.h, quality.fps)
        videoTrack = f.createVideoTrack("v0", videoSource).also {
            it.setEnabled(true); p.addTrack(it, listOf("s0")); localSink?.let { s -> it.addSink(s) }
        }
    }

    fun createOffer(send: (SessionDescription) -> Unit) {
        val p = pc ?: return
        p.createOffer(object : SdpObs() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(object : SdpObs() {
                    override fun onSetSuccess() { applyBitrate(); send(d) } // only now is the offer valid to send
                    override fun onSetFailure(e: String?) { log("set local offer failed: $e") }
                }, d)
            }
            override fun onCreateFailure(e: String?) { log("offer failed: $e") }
        }, MediaConstraints())
    }

    fun acceptOffer(sdp: String, send: (SessionDescription) -> Unit) {
        val p = pc ?: return
        p.setRemoteDescription(object : SdpObs() {
            override fun onSetSuccess() {
                remoteSet = true; flush()
                p.createAnswer(object : SdpObs() {
                    override fun onCreateSuccess(d: SessionDescription) {
                        p.setLocalDescription(object : SdpObs() {
                            override fun onSetSuccess() { send(d) }
                            override fun onSetFailure(e: String?) { log("set local answer failed: $e") }
                        }, d)
                    }
                    override fun onCreateFailure(e: String?) { log("answer failed: $e") }
                }, MediaConstraints())
            }
            override fun onSetFailure(e: String?) { log("set remote offer failed: $e") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun acceptAnswer(sdp: String) {
        pc?.setRemoteDescription(object : SdpObs() {
            override fun onSetSuccess() { remoteSet = true; flush() }
            override fun onSetFailure(e: String?) { log("set remote answer failed: $e") }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun addRemoteIce(c: IceCandidate) { if (remoteSet) pc?.addIceCandidate(c) else pending.add(c) }
    private fun flush() { synchronized(pending) { pending.forEach { pc?.addIceCandidate(it) }; pending.clear() } }

    private fun applyBitrate() {
        runCatching {
            pc?.senders?.firstOrNull { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND }?.let { s ->
                val p = s.parameters
                p.encodings.forEach { it.maxBitrateBps = quality.bitrate; it.maxFramerate = quality.fps }
                s.setParameters(p)
            }
        }
    }

    fun setQuality(q: Quality) {
        quality = q
        runCatching { (capturer as? CameraVideoCapturer)?.changeCaptureFormat(q.w, q.h, q.fps) }
        applyBitrate()
    }

    fun setMuted(m: Boolean) { audioTrack?.setEnabled(!m); upd { it.copy(mic = if (m) "muted" else "live") } }
    fun setSpeaker(on: Boolean) { am.isSpeakerphoneOn = on }

    fun switchCamera(done: (Boolean) -> Unit) {
        (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { done(front) }
            override fun onCameraSwitchError(e: String?) { upd { it.copy(camera = "switch error: $e") } }
        })
    }

    fun attachRemote(r: VideoSink?) { remoteSink?.let { remoteTrack?.removeSink(it) }; remoteSink = r; r?.let { remoteTrack?.addSink(it) } }
    fun attachLocal(r: VideoSink?) { localSink?.let { videoTrack?.removeSink(it) }; localSink = r; r?.let { videoTrack?.addSink(it) } }
    fun markFirstFrame() { upd { it.copy(firstFrame = true) } }

    fun pollStats() {
        val p = pc ?: return
        p.getStats { report ->
            fun n(v: Any?) = (v as? Number)?.toDouble()
            val sb = StringBuilder(); var bytes = 0.0
            for (s in report.statsMap.values) {
                val m = s.members
                when (s.type) {
                    "inbound-rtp" -> if (m["kind"] == "video") {
                        bytes = n(m["bytesReceived"]) ?: 0.0
                        sb.append("fps ${n(m["framesPerSecond"])?.toInt() ?: "-"}  loss ${n(m["packetsLost"])?.toInt() ?: "-"}  jitter ${n(m["jitter"])?.let { "%.0fms".format(it * 1000) } ?: "-"}  ")
                    }
                    "outbound-rtp" -> if (m["kind"] == "video") {
                        bytes = n(m["bytesSent"]) ?: 0.0
                        sb.append("fps ${n(m["framesPerSecond"])?.toInt() ?: "-"}  ")
                    }
                    "candidate-pair" -> if (m["state"] == "succeeded" && m["nominated"] == true)
                        sb.append("rtt ${n(m["currentRoundTripTime"])?.let { "%.0fms".format(it * 1000) } ?: "-"}  ")
                }
            }
            val now = System.currentTimeMillis()
            if (lastT > 0 && bytes >= lastBytes && now > lastT) sb.append("%.0f kbps".format((bytes - lastBytes) * 8 / (now - lastT)))
            lastBytes = bytes; lastT = now
            upd { it.copy(stats = sb.toString().trim()) }
        }
    }

    /** Stops camera + mic, closes the connection, releases audio focus. Factory is kept for reuse. */
    fun release() {
        runCatching { (capturer as? CameraVideoCapturer)?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { helper?.dispose() }; helper = null
        runCatching { localSink?.let { videoTrack?.removeSink(it) } }
        runCatching { remoteSink?.let { remoteTrack?.removeSink(it) } }
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.setEnabled(false); audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        remoteTrack = null
        runCatching { pc?.close() }; runCatching { pc?.dispose() }; pc = null
        remoteSet = false; pending.clear(); lastT = 0
        @Suppress("DEPRECATION") am.abandonAudioFocus(null)
        am.isSpeakerphoneOn = false; am.mode = AudioManager.MODE_NORMAL
        state.value = RtcState()
    }

    fun destroy() {
        release()
        runCatching { factory?.dispose() }; factory = null
        runCatching { egl.release() }
    }
}

// ───────────────────────────── FOREGROUND SERVICE ─────────────────────────────

class StreamService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("fpv", "FPV session", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "fpv")
            .setContentTitle("Offline FPV Link").setContentText("Session active")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (i?.getBooleanExtra("cam", false) == true) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(1, n, t)
            } else startForeground(1, n)
        } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
}

// ───────────────────────────── SETTINGS STORE ─────────────────────────────

private val Context.dataStore by preferencesDataStore("fpv_settings")
private object K {
    val quality = stringPreferencesKey("quality"); val muted = booleanPreferencesKey("muted")
    val stats = booleanPreferencesKey("stats"); val port = intPreferencesKey("port")
    val front = booleanPreferencesKey("front"); val host = stringPreferencesKey("host")
    val verbose = booleanPreferencesKey("verbose")
    val noNetMon = booleanPreferencesKey("no_net_monitor")
}

// ───────────────────────────── VIEW MODEL (session controller) ─────────────────────────────

@SuppressLint("MissingPermission")
class FpvViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private val p2p = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = p2p?.initialize(ctx, ctx.mainLooper, null)
    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private var sessionActive = false
    private var peerRole: String? = null
    private var jobs = mutableListOf<Job>()
    private var connectTimeout: Job? = null

    private val signaling = LocalSignaling(viewModelScope,
        onMsg = { inbox.trySend(it) },
        onState = { s -> _ui.update { it.copy(sigState = s) }; log("signaling: $s") },
        onPeerClosed = { viewModelScope.launch { onPeerClosed() } })

    // Outgoing ICE is held until our SDP has actually been sent, so the peer never sees candidates first.
    private val outIce = mutableListOf<IceCandidate>()
    private var sdpSent = false

    val rtc = WebRtcManager(ctx, onIce = ::onLocalIce, log = ::log,
        disableNetMonitor = { _ui.value.settings.disableNetMonitor })

    private fun onLocalIce(c: IceCandidate) {
        log("local candidate: ${c.sdp.take(90)}") // shows which interfaces (e.g. p2p-wlan0) actually gather
        synchronized(outIce) {
            if (sdpSent) signaling.send(Msg("ice", mid = c.sdpMid, idx = c.sdpMLineIndex, cand = c.sdp)) else outIce.add(c)
        }
    }

    private fun sendSdp(type: String, sdp: String) {
        synchronized(outIce) {
            signaling.send(Msg(type, sdp = sdp)); sdpSent = true
            outIce.forEach { signaling.send(Msg("ice", mid = it.sdpMid, idx = it.sdpMLineIndex, cand = it.sdp)) }
            outIce.clear()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _ui.update { it.copy(p2pEnabled = on) }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ch = channel ?: return
                    p2p?.requestConnectionInfo(ch) { info -> onConnInfo(info) }
                }
            }
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (p2p == null || channel == null) _ui.update { it.copy(p2pEnabled = false) }
        viewModelScope.launch { for (m in inbox) handle(m) }
        viewModelScope.launch {
            val p = ctx.dataStore.data.first()
            val s = AppSettings(
                quality = runCatching { Quality.valueOf(p[K.quality] ?: "LOW") }.getOrDefault(Quality.LOW),
                startMuted = p[K.muted] ?: false, showStats = p[K.stats] ?: true,
                port = (p[K.port] ?: 8988).takeIf { it in 1024..65535 } ?: 8988,
                frontCamera = p[K.front] ?: false, localHost = p[K.host] ?: "", verboseLog = p[K.verbose] ?: true,
                disableNetMonitor = p[K.noNetMon] ?: false)
            _ui.update { it.copy(settings = s, quality = s.quality, front = s.frontCamera, muted = s.startMuted) }
        }
    }

    // ── logging / progress ──
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(t: String) {
        if (!_ui.value.settings.verboseLog) return
        _ui.update { it.copy(logs = (it.logs + "${fmt.format(Date())}  $t").takeLast(200)) } // bounded; never log tokens
    }
    private fun step(t: String) { log(t); _ui.update { it.copy(progress = it.progress + t) } }
    private fun fail(t: String) { log("ERROR: $t"); _ui.update { it.copy(error = t) } }
    fun clearLogs() = _ui.update { it.copy(logs = emptyList()) }

    // ── navigation ──
    fun go(s: Screen) = _ui.update { it.copy(screen = s) }
    fun setMode(m: Mode) = _ui.update { it.copy(mode = m) }
    fun chooseRole(r: Role) {
        _ui.update { it.copy(role = r, screen = Screen.DISCOVERY, error = null, progress = emptyList(), peers = emptyList(), localIps = localIps()) }
    }
    fun back() {
        when (_ui.value.screen) {
            Screen.DISCOVERY, Screen.PROGRESS -> endSession(false, null)
            Screen.SETTINGS, Screen.DIAG -> go(if (_ui.value.role == null) Screen.HOME else _ui.value.screen.let { if (sessionActive) (if (_ui.value.role == Role.TRANSMITTER) Screen.TX else Screen.VIEWER) else Screen.DISCOVERY })
            else -> {}
        }
    }

    // ── Wi-Fi Direct ──
    fun startDiscovery() {
        val ch = channel ?: return fail("Wi-Fi Direct is not available on this device.")
        _ui.update { it.copy(error = null) }
        p2p?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _ui.update { it.copy(discovering = true) }; log("discovery started") }
            override fun onFailure(r: Int) { _ui.update { it.copy(discovering = false) }; fail("Discovery failed: ${reason(r)}. Make sure Wi-Fi is on.") }
        })
    }
    fun stopDiscovery() {
        val ch = channel ?: return
        p2p?.stopPeerDiscovery(ch, null); _ui.update { it.copy(discovering = false) }
    }
    private fun refreshPeers() {
        val ch = channel ?: return
        p2p?.requestPeers(ch) { list ->
            val peers = list.deviceList.map { Peer(it.deviceName.ifBlank { "Unnamed device" }, it.deviceAddress, it.status) }
                .distinctBy { it.address }
            _ui.update { it.copy(peers = peers) }
        }
    }
    fun connectPeer(p: Peer) {
        val ch = channel ?: return
        stopDiscovery()
        _ui.update { it.copy(selected = p, screen = Screen.PROGRESS, progress = emptyList(), error = null) }
        step("Requesting connection to ${p.name}")
        val cfg = WifiP2pConfig().apply { deviceAddress = p.address; wps.setup = WpsInfo.PBC }
        p2p?.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { step("Request sent. Waiting for the other phone to accept") }
            override fun onFailure(r: Int) { fail("Connection request failed: ${reason(r)}") }
        })
        connectTimeout?.cancel()
        connectTimeout = viewModelScope.launch {
            delay(60_000)
            if (!sessionActive) { p2p?.cancelConnect(ch, null); fail("Group formation timed out. Retry, and make sure the other phone accepts the invitation.") }
        }
    }

    private fun onConnInfo(info: WifiP2pInfo) {
        val st = _ui.value
        if (st.mode != Mode.DIRECT || st.role == null) return
        if (!info.groupFormed) { if (sessionActive) viewModelScope.launch { onPeerClosed() }; return }
        if (sessionActive) return
        sessionActive = true; connectTimeout?.cancel()
        val ownerIp = info.groupOwnerAddress?.hostAddress
        _ui.update { it.copy(screen = Screen.PROGRESS, groupFormed = true, isGroupOwner = info.isGroupOwner, ownerIp = ownerIp, error = null) }
        step("Direct Wi-Fi group formed (this phone is ${if (info.isGroupOwner) "group owner" else "client"})")
        if (info.isGroupOwner) {
            step("Hosting local signaling, waiting for peer"); signaling.host(_ui.value.settings.port)
        } else if (ownerIp != null) {
            step("Locating signaling host at $ownerIp"); signaling.join(ownerIp, _ui.value.settings.port)
        } else { sessionActive = false; fail("Group formed but owner address is unknown.") }
    }

    // ── Local Wi-Fi fallback ──
    fun startLocalHost() {
        sessionActive = true
        _ui.update { it.copy(screen = Screen.PROGRESS, progress = emptyList(), error = null, localIps = localIps()) }
        step("Hosting signaling on port ${_ui.value.settings.port}. Viewer connects to: ${_ui.value.localIps.joinToString()}")
        signaling.host(_ui.value.settings.port)
    }
    fun joinLocal(ip: String) {
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip.trim())) return fail("Enter a valid IPv4 address.")
        sessionActive = true
        _ui.update { it.copy(screen = Screen.PROGRESS, progress = emptyList(), error = null) }
        step("Connecting to ${ip.trim()}"); signaling.join(ip.trim(), _ui.value.settings.port)
    }

    // ── signaling handshake + WebRTC ──
    private fun myCode() = _ui.value.role?.code ?: ""
    private fun mic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun cam() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startMedia() {
        val s = _ui.value
        rtc.start(s.role == Role.TRANSMITTER, s.quality, s.front, s.muted, mic(), cam())
        if (!mic()) fail("Microphone permission denied: voice disabled.")
        if (s.role == Role.TRANSMITTER && !cam()) fail("Camera permission denied: no video.")
    }

    private fun handle(m: Msg) {
        when (m.type) {
            "join" -> { // host side
                if (m.role == myCode()) { signaling.send(Msg("reject", text = "both phones chose the same role")); fail("Both phones chose the same role."); return }
                peerRole = m.role; step("Peer joined. Negotiating media")
                resetMedia() // peer may be reconnecting while our old PeerConnection is still alive
                startMedia(); signaling.send(Msg("accept", role = myCode())); handshakeDone()
            }
            "accept" -> {
                if (m.role == myCode()) { fail("Both phones chose the same role."); return }
                peerRole = m.role; step("Signaling established. Negotiating media"); resetMedia(); startMedia(); handshakeDone()
            }
            "reject" -> fail("Peer rejected the session: ${m.text ?: ""}")
            "offer" -> if (_ui.value.role == Role.VIEWER) rtc.acceptOffer(m.sdp!!) { sendSdp("answer", it.description) }
            "answer" -> if (_ui.value.role == Role.TRANSMITTER) rtc.acceptAnswer(m.sdp!!)
            "ice" -> rtc.addRemoteIce(IceCandidate(m.mid, m.idx, m.cand))
            "bye" -> endSession(false, "Peer ended the session.")
            "error" -> fail("Peer error: ${m.text ?: ""}")
        }
    }

    private fun handshakeDone() {
        val tx = _ui.value.role == Role.TRANSMITTER
        _ui.update { it.copy(handshake = true, connLost = false, startedAt = System.currentTimeMillis(),
            screen = if (tx) Screen.TX else Screen.VIEWER) }
        ContextCompat.startForegroundService(ctx, Intent(ctx, StreamService::class.java).putExtra("cam", tx))
        if (tx) { step("Sending offer"); rtc.createOffer { sendSdp("offer", it.description) } }
        else step("Waiting for first video frame")
        jobs += viewModelScope.launch { while (isActive) { delay(5000); signaling.send(Msg("ping")) } }
        jobs += viewModelScope.launch { while (isActive) { delay(2000); rtc.pollStats() } }
        if (!tx) { // viewer announces itself to the host path as well (client sends join before accept; see joinSent)
        }
    }

    /** Client side must announce itself once the TCP link is up. */
    private fun sendJoinIfClient() { signaling.send(Msg("join", role = myCode())) }

    init {
        // When the TCP link is up on the client side, send "join". Host side waits for it.
        viewModelScope.launch {
            _ui.map { it.sigState }.distinctUntilChanged().collect { s ->
                if (s == "connected" && !_ui.value.handshake && !_ui.value.isHostSide()) sendJoinIfClient()
            }
        }
    }
    private fun UiState.isHostSide(): Boolean =
        if (mode == Mode.DIRECT) isGroupOwner else role == Role.TRANSMITTER

    private fun onPeerClosed() {
        if (!sessionActive) return
        if (_ui.value.handshake) { log("peer connection lost"); _ui.update { it.copy(connLost = true) } }
        else fail("Signaling connection closed before media started.")
    }

    /** Bounded retry (max 3): rebuilds media + signaling over the existing group. */
    fun reconnect() {
        if (_ui.value.retries >= 3) return fail("Too many reconnect attempts. End the session and start again.")
        teardownMedia()
        _ui.update { it.copy(retries = it.retries + 1, connLost = false, error = null, progress = emptyList(), screen = Screen.PROGRESS) }
        step("Reconnecting (attempt ${_ui.value.retries}/3)")
        sessionActive = false
        if (_ui.value.mode == Mode.DIRECT) channel?.let { ch -> p2p?.requestConnectionInfo(ch) { onConnInfo(it) } }
        else if (_ui.value.role == Role.TRANSMITTER) startLocalHost() else joinLocal(_ui.value.settings.localHost)
    }

    /** Drops the current PeerConnection/tracks and per-session flags, but leaves signaling open. */
    private fun resetMedia() {
        jobs.forEach { it.cancel() }; jobs.clear()
        rtc.release()
        synchronized(outIce) { sdpSent = false; outIce.clear() }
        _ui.update { it.copy(handshake = false, connLost = false) }
    }

    private fun teardownMedia() {
        signaling.close() // first: intentional close must not trigger onPeerClosed
        resetMedia()
        ctx.stopService(Intent(ctx, StreamService::class.java))
        _ui.update { it.copy(sigState = "idle") }
    }

    fun endSession(notifyPeer: Boolean, message: String?) {
        if (notifyPeer) signaling.send(Msg("bye"))
        viewModelScope.launch {
            if (notifyPeer) delay(150)
            connectTimeout?.cancel()
            teardownMedia()
            channel?.let { ch -> p2p?.cancelConnect(ch, null); p2p?.removeGroup(ch, null); p2p?.stopPeerDiscovery(ch, null) }
            sessionActive = false; peerRole = null
            _ui.update { it.copy(screen = Screen.HOME, role = null, handshake = false, groupFormed = false, isGroupOwner = false,
                ownerIp = null, selected = null, peers = emptyList(), discovering = false, connLost = false, retries = 0,
                progress = emptyList(), error = message) }
        }
    }

    // ── media controls ──
    fun toggleMute() { val m = !_ui.value.muted; _ui.update { it.copy(muted = m) }; rtc.setMuted(m) }
    fun toggleSpeaker() { val s = !_ui.value.speaker; _ui.update { it.copy(speaker = s) }; rtc.setSpeaker(s) }
    fun switchCamera() { rtc.switchCamera { f -> _ui.update { it.copy(front = f) } } }
    fun setQuality(q: Quality) { _ui.update { it.copy(quality = q) }; rtc.setQuality(q) }

    // ── settings ──
    fun saveSettings(s: AppSettings) {
        val v = s.copy(port = s.port.coerceIn(1024, 65535))
        _ui.update { it.copy(settings = v) }
        viewModelScope.launch {
            ctx.dataStore.edit { p ->
                p[K.quality] = v.quality.name; p[K.muted] = v.startMuted; p[K.stats] = v.showStats
                p[K.port] = v.port; p[K.front] = v.frontCamera; p[K.host] = v.localHost; p[K.verbose] = v.verboseLog; p[K.noNetMon] = v.disableNetMonitor
            }
        }
    }

    private fun reason(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
        WifiP2pManager.BUSY -> "system busy, try again in a moment"
        WifiP2pManager.ERROR -> "internal error"
        else -> "code $r"
    }

    private fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${n.name})" } }
    }.getOrDefault(emptyList())

    override fun onCleared() {
        runCatching { ctx.unregisterReceiver(receiver) }
        teardownMedia()
        channel?.let { p2p?.removeGroup(it, null) }
        rtc.destroy()
    }
}

fun peerStatus(s: Int) = when (s) {
    WifiP2pDevice.AVAILABLE -> "Available"; WifiP2pDevice.INVITED -> "Invited"
    WifiP2pDevice.CONNECTED -> "Connected"; WifiP2pDevice.FAILED -> "Failed"; else -> "Unavailable"
}

// ───────────────────────────── ACTIVITY ─────────────────────────────

class MainActivity : ComponentActivity() {
    private val vm: FpvViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FpvTheme { App(vm) { on -> immersive(on) } } }
    }
    private fun immersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }
}

// ───────────────────────────── UI ─────────────────────────────

private val Cyan = Color(0xFF00E5FF)
private val Bg = Color(0xFF121417)
private val Surf = Color(0xFF1C2026)

@Composable
fun FpvTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Cyan, onPrimary = Color.Black, background = Bg, surface = Surf,
        onBackground = Color.White, onSurface = Color.White, secondary = Cyan, error = Color(0xFFFF6B6B)),
    content = content)

private fun requiredPerms(role: Role): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES) else add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.RECORD_AUDIO)
    if (role == Role.TRANSMITTER) add(Manifest.permission.CAMERA)
}

@Composable
fun App(vm: FpvViewModel, immersive: (Boolean) -> Unit) {
    val ui by vm.ui.collectAsState()
    val rtc by vm.rtc.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    SideEffect { view.keepScreenOn = ui.screen == Screen.TX || ui.screen == Screen.VIEWER }

    var pendingRole by remember { mutableStateOf<Role?>(null) }
    var denied by remember { mutableStateOf<List<String>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val role = pendingRole ?: return@rememberLauncherForActivityResult
        val missing = requiredPerms(role).filter { res[it] != true && ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { denied = emptyList(); vm.chooseRole(role) } else denied = missing
        pendingRole = null
    }
    fun ask(role: Role) {
        val missing = requiredPerms(role).filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) vm.chooseRole(role) else pendingRole = role
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().systemBarsPadding().let { if (ui.screen == Screen.VIEWER) Modifier.fillMaxSize() else it }) {
            when (ui.screen) {
                Screen.HOME -> Home(ui, vm, ::ask, denied) { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                Screen.DISCOVERY -> Discovery(ui, vm)
                Screen.PROGRESS -> Progress(ui, vm)
                Screen.TX -> Transmitter(ui, rtc, vm)
                Screen.VIEWER -> Viewer(ui, rtc, vm, immersive)
                Screen.SETTINGS -> SettingsScreen(ui, vm)
                Screen.DIAG -> Diagnostics(ui, rtc, vm)
            }
        }
    }
    pendingRole?.let { role ->
        AlertDialog(onDismissRequest = { pendingRole = null },
            title = { Text("Permissions needed") },
            text = { Text("• Nearby devices / location: find and connect to the other phone over Wi-Fi Direct.\n• Microphone: two-way voice.\n" + if (role == Role.TRANSMITTER) "• Camera: stream live video." else "") },
            confirmButton = { TextButton({ launcher.launch((requiredPerms(role) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray()) }) { Text("Continue") } },
            dismissButton = { TextButton({ pendingRole = null }) { Text("Cancel") } })
    }
}

@Composable
fun Pill(text: String, ok: Boolean? = null) {
    val c = when (ok) { true -> Color(0xFF2ECC71); false -> Color(0xFFFF6B6B); null -> Color(0xFF8A94A3) }
    Surface(shape = RoundedCornerShape(50), color = Surf) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(c, RoundedCornerShape(50))); Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 12.sp)
        }
    }
}

@Composable
fun Big(text: String, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier.heightIn(min = 56.dp).semantics { contentDescription = text }, enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White) else ButtonDefaults.buttonColors()) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }

@Composable
fun Home(ui: UiState, vm: FpvViewModel, ask: (Role) -> Unit, denied: List<String>, openSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Offline FPV Link", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Cyan)
        Text("Direct phone-to-phone FPV video and voice. No internet needed.", color = Color(0xFFB0B8C4))
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Connection mode", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { FilterChip(ui.mode == it, { vm.setMode(it) }, { Text(it.label) }) }
            FilterChip(false, {}, { Text("Internet (future)") }, enabled = false)
        }
        Big("Transmitter: phone on the robot", Modifier.fillMaxWidth()) { ask(Role.TRANSMITTER) }
        Big("Viewer: FPV operator", Modifier.fillMaxWidth()) { ask(Role.VIEWER) }
        if (denied.isNotEmpty()) {
            Text("Permission denied. The app can't work without it.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(openSettings) { Text("Open app settings") }
        }
        Row { TextButton({ vm.go(Screen.SETTINGS) }) { Text("Settings") }; TextButton({ vm.go(Screen.DIAG) }) { Text("Diagnostics") } }
        Text("Tip: if the phones are close together, use headphones to avoid audio feedback.", fontSize = 12.sp, color = Color(0xFF8A94A3))
    }
}

@Composable
fun Discovery(ui: UiState, vm: FpvViewModel) {
    var ip by remember { mutableStateOf(ui.settings.localHost) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${ui.role?.name} · ${ui.mode.label}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (ui.mode == Mode.DIRECT) {
            Pill(when (ui.p2pEnabled) { true -> "Wi-Fi Direct ready"; false -> "Wi-Fi Direct off/unavailable. Turn Wi-Fi on"; null -> "Checking Wi-Fi Direct" }, ui.p2pEnabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Big(if (ui.discovering) "Refresh" else "Start discovery", Modifier.weight(1f)) { vm.startDiscovery() }
                if (ui.discovering) OutlinedButton({ vm.stopDiscovery() }, Modifier.heightIn(min = 56.dp)) { Text("Stop") }
            }
            Text("Both phones should be on this screen. Tap the other phone; it must accept the invitation.", fontSize = 12.sp, color = Color(0xFF8A94A3))
            if (ui.peers.isEmpty()) Text(if (ui.discovering) "Searching for nearby devices…" else "No devices yet. Start discovery.", color = Color(0xFFB0B8C4))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.peers, key = { it.address }) { p ->
                    Surface(shape = RoundedCornerShape(16.dp), color = Surf, modifier = Modifier.fillMaxWidth().clickable { vm.connectPeer(p) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold); Text(p.address, fontSize = 12.sp, color = Color(0xFF8A94A3))
                            Text(peerStatus(p.status), fontSize = 12.sp, color = Cyan)
                        }
                    }
                }
            }
        } else if (ui.role == Role.TRANSMITTER) {
            Text("This phone hosts signaling. Phones must already be on the same local network.")
            Text("Your addresses: ${ui.localIps.joinToString().ifEmpty { "none found" }}")
            Big("Start hosting", Modifier.fillMaxWidth()) { vm.startLocalHost() }
        } else {
            OutlinedTextField(ip, { ip = it }, label = { Text("Transmitter IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Big("Connect", Modifier.fillMaxWidth()) { vm.saveSettings(ui.settings.copy(localHost = ip)); vm.joinLocal(ip) }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton({ vm.back() }, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
fun Progress(ui: UiState, vm: FpvViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connecting", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        ui.selected?.let { Text("Peer: ${it.name}") }
        ui.progress.forEach { Text("• $it") }
        Pill("Signaling: ${ui.sigState}", if (ui.sigState == "connected") true else null)
        if (ui.error == null) CircularProgressIndicator()
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error); Big("Retry", Modifier.fillMaxWidth()) { vm.reconnect() } }
        Spacer(Modifier.weight(1f))
        OutlinedButton({ vm.endSession(false, null) }, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
fun VideoSurface(rtc: WebRtcManager, local: Boolean, fill: Boolean, mirror: Boolean, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { c ->
        SurfaceViewRenderer(c).apply {
            init(rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() { if (!local) rtc.markFirstFrame() }
                override fun onFrameResolutionChanged(w: Int, h: Int, r: Int) {}
            })
            setEnableHardwareScaler(true)
            if (local) rtc.attachLocal(this) else rtc.attachRemote(this)
        }
    }, update = {
        it.setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        it.setMirror(mirror)
    }, onRelease = { if (local) rtc.attachLocal(null) else rtc.attachRemote(null); it.release() })
}

@Composable
fun Transmitter(ui: UiState, rtc: RtcState, vm: FpvViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VideoSurface(vm.rtc, true, false, ui.front, Modifier.fillMaxSize())
            if (rtc.camera != "live") Text("Camera: ${rtc.camera}", Modifier.align(Alignment.Center))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Peer ${rtc.pc}", rtc.pc == "CONNECTED"); Pill("Mic ${rtc.mic}", rtc.mic == "live"); Pill("Remote audio", rtc.remoteAudio)
        }
        if (ui.settings.showStats) Text(rtc.stats, fontSize = 11.sp, color = Cyan)
        if (ui.connLost) Text("Viewer disconnected.", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Quality.entries.forEach { FilterChip(ui.quality == it, { vm.setQuality(it) }, { Text(it.label, fontSize = 11.sp) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big(if (ui.muted) "Unmute" else "Mute", Modifier.weight(1f)) { vm.toggleMute() }
            Big("Flip cam", Modifier.weight(1f)) { vm.switchCamera() }
            Big(if (ui.speaker) "Earpiece" else "Speaker", Modifier.weight(1f)) { vm.toggleSpeaker() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.connLost) Big("Reconnect", Modifier.weight(1f)) { vm.reconnect() }
            Big("End session", Modifier.weight(1f), danger = true) { vm.endSession(true, null) }
            TextButton({ vm.go(Screen.DIAG) }) { Text("Diag") }
        }
    }
}

@Composable
fun Viewer(ui: UiState, rtc: RtcState, vm: FpvViewModel, immersive: (Boolean) -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    val secs by produceState(0L, ui.startedAt) { while (true) { value = (System.currentTimeMillis() - ui.startedAt) / 1000; delay(1000) } }
    DisposableEffect(Unit) { onDispose { immersive(false) } }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { controls = !controls }) {
        VideoSurface(vm.rtc, false, fill, false, Modifier.fillMaxSize())
        if (!rtc.firstFrame && !ui.connLost) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(if (rtc.remoteVideo) "Waiting for first video frame" else "Waiting for remote video track")
        }
        if (ui.connLost) Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Connection lost", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Big("Reconnect") { vm.reconnect() }
        }
        ui.error?.let { Text(it, Modifier.align(Alignment.TopCenter).padding(8.dp), color = MaterialTheme.colorScheme.error) }
        if (controls) {
            Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(rtc.pc, rtc.pc == "CONNECTED"); Pill("Voice ${if (rtc.remoteAudio) "on" else "n/a"}", rtc.remoteAudio)
                    Pill("${ui.quality.label}"); Pill("%02d:%02d".format(secs / 60, secs % 60))
                }
                if (ui.settings.showStats) Text(rtc.stats, fontSize = 11.sp, color = Cyan)
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Big(if (ui.muted) "Unmute" else "Mute") { vm.toggleMute() }
                Big(if (ui.speaker) "Earpiece" else "Speaker") { vm.toggleSpeaker() }
                Big(if (fill) "Fit" else "Fill") { fill = !fill }
                Big(if (full) "Exit full" else "Full") { full = !full; immersive(full) }
                Big("End", danger = true) { vm.endSession(true, null) }
            }
        }
    }
}

@Composable
fun SettingsScreen(ui: UiState, vm: FpvViewModel) {
    val s = ui.settings
    var port by remember { mutableStateOf(s.port.toString()) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Default video quality")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Quality.entries.forEach { q -> FilterChip(s.quality == q, { vm.saveSettings(s.copy(quality = q)) }, { Text(q.label, fontSize = 11.sp) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Start muted", Modifier.weight(1f)); Switch(s.startMuted, { vm.saveSettings(s.copy(startMuted = it)) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Statistics overlay", Modifier.weight(1f)); Switch(s.showStats, { vm.saveSettings(s.copy(showStats = it)) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Prefer front camera", Modifier.weight(1f)); Switch(s.frontCamera, { vm.saveSettings(s.copy(frontCamera = it)) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Diagnostic logging", Modifier.weight(1f)); Switch(s.verboseLog, { vm.saveSettings(s.copy(verboseLog = it)) }) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Disable WebRTC network monitor (experimental, force-stop and relaunch the app after changing)", Modifier.weight(1f), fontSize = 13.sp)
            Switch(s.disableNetMonitor, { vm.saveSettings(s.copy(disableNetMonitor = it)) })
        }
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Signaling port (1024–65535)") }, singleLine = true,
            isError = port.toIntOrNull()?.let { it !in 1024..65535 } ?: true)
        Big("Save port") { port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { vm.saveSettings(s.copy(port = it)) } }
        OutlinedButton({ vm.go(if (ui.handshake) (if (ui.role == Role.VIEWER) Screen.VIEWER else Screen.TX) else if (ui.role != null) Screen.DISCOVERY else Screen.HOME) }, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
fun Diagnostics(ui: UiState, rtc: RtcState, vm: FpvViewModel) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Diagnostics", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            listOf(
                "Wi-Fi Direct" to "${ui.p2pEnabled} · discovering=${ui.discovering}",
                "Selected peer" to (ui.selected?.name ?: "-"),
                "Group formed / owner" to "${ui.groupFormed} / ${ui.isGroupOwner} (${ui.ownerIp ?: "-"})",
                "Signaling (TCP)" to "${ui.sigState} · handshake=${ui.handshake}",
                "WebRTC signaling" to rtc.signaling, "ICE gathering" to rtc.gathering, "ICE connection" to rtc.ice, "Peer connection" to rtc.pc,
                "Camera" to rtc.camera, "Microphone" to rtc.mic,
                "Remote video track / first frame" to "${rtc.remoteVideo} / ${rtc.firstFrame}", "Remote audio track" to "${rtc.remoteAudio}",
                "Stats" to rtc.stats.ifEmpty { "-" }, "Last error" to (ui.error ?: "-")
            ).forEach { (k, v) -> Text("$k: $v", fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp)); Text("Log", fontWeight = FontWeight.SemiBold)
            ui.logs.forEach { Text(it, fontSize = 11.sp, color = Color(0xFFB0B8C4)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clip.setText(AnnotatedString(ui.logs.joinToString("\n"))) }) { Text("Copy log") }
            OutlinedButton({ vm.clearLogs() }) { Text("Clear") }
            OutlinedButton({ vm.go(if (ui.handshake) (if (ui.role == Role.VIEWER) Screen.VIEWER else Screen.TX) else if (ui.role != null) Screen.DISCOVERY else Screen.HOME) }) { Text("Back") }
        }
    }
}