package com.fox.audiosplit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException

object Status {
    @Volatile var text = "Belum jalan"
}

class SplitService : Service() {
    private lateinit var am: AudioManager
    private val h = Handler(Looper.getMainLooper())
    private var policy: Any? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var currentDev = -1
    private var strategyForced = false

    private val ext = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )

    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(a: Array<AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(a: Array<AudioDeviceInfo>) = refresh()
    }

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("L")
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(cb, h)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        goForeground()
        if (i?.action == "RESTART") stopRouting()
        refresh()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("split", "Audio Split", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "split")
            .setContentTitle("Audio Split aktif")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun refresh() {
        val prefs = getSharedPreferences("cfg", 0)
        val pkg = prefs.getString("pkg", "com.bitchord") ?: return
        val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in ext }
        if (dev == null) {
            stopRouting()
            Status.text = "Tidak ada perangkat eksternal: audio normal"
            return
        }
        if (running && currentDev == dev.id) return
        stopRouting()
        try {
            startRouting(pkg, dev, prefs.getBoolean("others_speaker", true))
            Status.text = "Aktif: $pkg -> ${dev.productName}"
        } catch (t: Throwable) {
            val e = (t as? InvocationTargetException)?.targetException ?: t
            Status.text = "Gagal: $e"
            stopRouting()
        }
    }

    private fun startRouting(pkg: String, dev: AudioDeviceInfo, othersToSpeaker: Boolean) {
        val uid = packageManager.getApplicationInfo(pkg, 0).uid
        val ap = "android.media.audiopolicy."
        val ruleCls = Class.forName(ap + "AudioMixingRule")
        val ruleB = Class.forName(ap + "AudioMixingRule\$Builder")
        val mixCls = Class.forName(ap + "AudioMix")
        val mixB = Class.forName(ap + "AudioMix\$Builder")
        val polCls = Class.forName(ap + "AudioPolicy")
        val polB = Class.forName(ap + "AudioPolicy\$Builder")

        val rb = ruleB.getConstructor().newInstance()
        val ruleUid = ruleCls.getField("RULE_MATCH_UID").getInt(null)
        ruleB.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(rb, ruleUid, uid)
        runCatching {
            ruleB.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.javaPrimitiveType)
                .invoke(rb, true)
        }
        val rule = ruleB.getMethod("build").invoke(rb)

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val mb = mixB.getConstructor(ruleCls).newInstance(rule)
        mixB.getMethod("setFormat", AudioFormat::class.java).invoke(mb, fmt)
        mixB.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mb, mixCls.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        val mix = mixB.getMethod("build").invoke(mb)

        val pb = polB.getConstructor(Context::class.java).newInstance(this)
        polB.getMethod("addMix", mixCls).invoke(pb, mix)
        polB.getMethod("setLooper", Looper::class.java).invoke(pb, Looper.getMainLooper())
        val pol = polB.getMethod("build").invoke(pb)
        val r = AudioManager::class.java.getMethod("registerAudioPolicy", polCls).invoke(am, pol) as Int
        if (r != 0) throw IllegalStateException("registerAudioPolicy=$r (izin privileged belum diberikan?)")
        policy = pol

        val rec = polCls.getMethod("createAudioRecordSink", mixCls).invoke(pol, mix) as AudioRecord
        val minBuf = AudioTrack.getMinBufferSize(
            48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = minBuf * 4
        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        tr.preferredDevice = dev
        record = rec
        track = tr

        if (othersToSpeaker) runCatching { forceMediaSpeaker(true) }

        rec.startRecording()
        tr.play()
        running = true
        currentDev = dev.id
        worker = Thread {
            val buf = ByteArray(bufSize)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) tr.write(buf, 0, n) else if (n < 0) break
            }
        }.also { it.start() }
    }

    private fun forceMediaSpeaker(on: Boolean) {
        val sc = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        val list = sc.getMethod("getAudioProductStrategies").invoke(null) as List<*>
        val s = list.firstOrNull { (sc.getMethod("getName").invoke(it) as String).contains("MEDIA", true) }
            ?: return
        if (on) {
            val dc = Class.forName("android.media.AudioDeviceAttributes")
            val role = dc.getField("ROLE_OUTPUT").getInt(null)
            val d = dc.getConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            ).newInstance(role, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")
            AudioManager::class.java.getMethod("setPreferredDeviceForStrategy", sc, dc).invoke(am, s, d)
            strategyForced = true
        } else {
            AudioManager::class.java.getMethod("removePreferredDeviceForStrategy", sc).invoke(am, s)
            strategyForced = false
        }
    }

    private fun stopRouting() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { worker?.join(500) }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        policy?.let { p ->
            runCatching {
                val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
                AudioManager::class.java.getMethod("unregisterAudioPolicy", polCls).invoke(am, p)
            }
        }
        if (strategyForced) runCatching { forceMediaSpeaker(false) }
        policy = null; record = null; track = null; worker = null; currentDev = -1
    }

    override fun onDestroy() {
        am.unregisterAudioDeviceCallback(cb)
        stopRouting()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
