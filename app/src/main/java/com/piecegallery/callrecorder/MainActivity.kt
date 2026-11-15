package com.piecegallery.callrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val BG = 0xFF071514.toInt()
    private val CARD = 0xFF1C302F.toInt()
    private val ACCENT = 0xFF00D5D5.toInt()
    private val TEXT = 0xFFE7F0EF.toInt()
    private val SUB = 0xFFAABDBB.toInt()
    private val GREEN = 0xFF20C878.toInt()
    private val BLUE = 0xFF4285F4.toInt()
    private val RED = 0xFFFF6262.toInt()

    private lateinit var listBox: LinearLayout
    private lateinit var statusBtn: Button
    private lateinit var statusText: TextView
    private var filter = "ALL"
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private val prefs: SharedPreferences get() = getSharedPreferences(CallDetectionService.PREFS, MODE_PRIVATE)
    private val dir: File get() = getExternalFilesDir(null) ?: filesDir

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNeededPermissions()
        startService(Intent(this, CallDetectionService::class.java))
    }

    override fun onResume() { super.onResume(); refreshStatus(); refreshList() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    private fun buildUi() {
        val root = ScrollView(this)
        root.setBackgroundColor(BG)
        root.setFillViewport(true)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(32))
        root.addView(col)

        col.addView(tv("Simple Call Recorder", 24f, TEXT))
        col.addView(tv("OPPO / Android Edition", 13f, SUB))

        statusText = tv("", 14f, SUB)
        statusText.setPadding(0, dp(16), 0, dp(4))
        col.addView(statusText)
        statusBtn = Button(this)
        statusBtn.setTextColor(BG)
        col.addView(statusBtn)
        statusBtn.setOnClickListener {
            val p = prefs
            val now = !p.getBoolean(CallDetectionService.KEY_AUTO, true)
            p.edit().putBoolean(CallDetectionService.KEY_AUTO, now).apply()
            refreshStatus()
        }

        val btnRow = LinearLayout(this)
        btnRow.setPadding(0, dp(12), 0, 0)
        col.addView(btnRow)
        listOf("Mic Test" to ::runMicTest, "Refresh" to ::refreshList).forEach { (label, fn) ->
            val b = Button(this)
            b.text = label
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            btnRow.addView(b)
            b.setOnClickListener { fn() }
        }

        val tabRow = LinearLayout(this)
        tabRow.setPadding(0, dp(16), 0, dp(8))
        col.addView(tabRow)
        listOf("ALL", "Incoming", "Outgoing").forEach { f ->
            val b = Button(this)
            b.text = if (f == "ALL") "All" else f
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            b.setOnClickListener { filter = f; refreshList() }
            b.tag = f
            tabRow.addView(b)
        }

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        col.addView(listBox)
        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun refreshStatus() {
        val on = prefs.getBoolean(CallDetectionService.KEY_AUTO, true)
        statusText.text = if (on) "Automatic recording enabled" else "Automatic recording disabled"
        statusBtn.text = if (on) "Automatic Recording: ON (tap to turn off)" else "Automatic Recording: OFF (tap to turn on)"
    }

    private fun refreshList() {
        listBox.removeAllViews()
        stopPlayback()
        val files = dir.listFiles { f -> f.name.endsWith(".m4a") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val shown = files.filter { f ->
            when (filter) {
                "Incoming" -> f.name.contains("_Incoming")
                "Outgoing" -> f.name.contains("_Outgoing")
                else -> true
            }
        }
        (tabRowOf()).forEach { b ->
            val active = (b.tag as String) == filter
            b.setTextColor(if (active) ACCENT else SUB)
        }
        if (shown.isEmpty()) {
            val empty = tv("No recordings yet.\nMake a call with Automatic Recording ON.", 14f, SUB)
            empty.setPadding(0, dp(24), 0, 0)
            listBox.addView(empty)
            return
        }
        shown.forEach { f -> listBox.addView(recordingCard(f)) }
    }

    private fun tabRowOf(): List<Button> {
        val out = mutableListOf<Button>()
        for (i in 0 until listBox.parent.childCount) {
            val v = listBox.parent.getChildAt(i)
            if (v is LinearLayout) for (j in 0 until v.childCount) {
                val c = v.getChildAt(j)
                if (c is Button && c.tag is String) out.add(c)
            }
        }
        return out
    }

    private fun recordingCard(f: File): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundColor(CARD)
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }

        val silent = f.name.startsWith("Silent_")
        val incoming = f.name.contains("_Incoming")
        val name = tv(if (silent) "Unknown Number (NO AUDIO CAPTURED)" else "Unknown Number", 16f, if (silent) RED else TEXT)
        card.addView(name)
        card.addView(tv(if (incoming) "Incoming Call" else "Outgoing Call", 13f, if (incoming) GREEN else BLUE))
        val d = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.US).format(Date(f.lastModified()))
        val kb = f.length() / 1024
        card.addView(tv("$d · $kb KB\nMIC — Direct Microphone Capture", 12f, SUB))

        val row = LinearLayout(this)
        row.setPadding(0, dp(8), 0, 0)
        card.addView(row)
        val play = Button(this); play.text = "Play"; play.tag = f.absolutePath
        val share = Button(this); share.text = "Share"
        val del = Button(this); del.text = "Delete"; del.setTextColor(RED)
        listOf(play, share, del).forEach { b ->
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            row.addView(b)
        }
        play.setOnClickListener { v -> togglePlay(f, v as Button) }
        share.setOnClickListener { shareFile(f) }
        del.setOnClickListener { confirmDelete(f) }
        return card
    }

    private fun togglePlay(f: File, btn: Button) {
        if (playingPath == f.absolutePath && player?.isPlaying == true) {
            player?.pause(); btn.text = "Play"; return
        }
        if (playingPath == f.absolutePath && player != null) {
            player?.start(); btn.text = "Pause"; return
        }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { btn.text = "Play" }
                start()
            }
            playingPath = f.absolutePath
            btn.text = "Pause"
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: file may be silent or invalid", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayback() {
        player?.release(); player = null; playingPath = null
    }

    private fun shareFile(f: File) {
        val uri = Uri.parse("content://com.piecegallery.callrecorder.files/${f.name}")
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share recording"))
    }

    private fun confirmDelete(f: File) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(24), dp(24), dp(24), dp(8))
        row.addView(tv("Delete this recording?", 16f, TEXT))
        val r2 = LinearLayout(this)
        r2.setPadding(0, dp(16), 0, 0)
        val yes = Button(this); yes.text = "Delete"
        val no = Button(this); no.text = "Cancel"
        listOf(yes, no).forEach { b -> b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }; r2.addView(b) }
        row.addView(r2)
        val dlg = android.app.AlertDialog.Builder(this).setView(row).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        yes.setOnClickListener { f.delete(); dlg.dismiss(); refreshList() }
        no.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun runMicTest() {
        Toast.makeText(this, "Speak for 5 seconds after the beep toast...", Toast.LENGTH_SHORT).show()
        try {
            val f = File(dir, "MicTest.m4a")
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(f.absolutePath)
            rec.prepare(); rec.start()
            var max = 0
            val t = object : Runnable {
                var n = 0
                override fun run() {
                    max = maxOf(max, try { rec.maxAmplitude } catch (_: Exception) { 0 })
                    n += 500
                    if (n < 5000) handler.postDelayed(this, 500)
                    else {
                        try { rec.stop() } catch (_: Exception) {}
                        rec.release()
                        val ok = max >= 600
                        val msg = if (ok) "MICROPHONE WORKING (level $max)" else "MICROPHONE NOT WORKING (level $max)"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        if (ok) togglePlay(f, null)
                    }
                }
            }
            handler.postDelayed(t, 500)
        } catch (e: Exception) {
            Toast.makeText(this, "Mic test failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
