package com.fox.audiosplit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = getSharedPreferences("cfg", 0)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        val title = TextView(this).apply { text = "Package name app yang diarahkan ke TWS/headset:" }
        val et = EditText(this).apply { setText(p.getString("pkg", "com.bitchord")) }
        val cb = CheckBox(this).apply {
            text = "Paksa audio app lain ke speaker saat TWS tersambung"
            isChecked = p.getBoolean("others_speaker", true)
        }
        val st = TextView(this).apply { text = Status.text }
        val save = Button(this).apply {
            text = "Simpan & mulai ulang"
            setOnClickListener {
                p.edit().putString("pkg", et.text.toString().trim())
                    .putBoolean("others_speaker", cb.isChecked).apply()
                startForegroundService(
                    Intent(this@MainActivity, SplitService::class.java).setAction("RESTART")
                )
                postDelayed({ st.text = Status.text }, 1500)
            }
        }
        val refresh = Button(this).apply {
            text = "Refresh status"
            setOnClickListener { st.text = Status.text }
        }
        listOf(title, et, cb, save, refresh, st).forEach { root.addView(it) }
        setContentView(root)
        startForegroundService(Intent(this, SplitService::class.java))
    }
}
