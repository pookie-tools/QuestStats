package com.example.queststats

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var live: TextView
    private lateinit var startBtn: Button
    private val refresher = object : Runnable {
        override fun run() {
            live.text = if (SharedState.running) {
                SharedState.statsText + "\n\n" + SharedState.predictionText
            } else "Service stopped."
            startBtn.text = if (SharedState.running) "Stop overlay" else "Start overlay"
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var col: LinearLayout

    private fun header(t: String) {
        col.addView(TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        col.addView(Switch(this).apply {
            text = label
            isChecked = initial
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    private fun spinner(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        col.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
        col.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }

    private fun textField(label: String, initial: String, numeric: Boolean, onText: (String) -> Unit) {
        col.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
        col.addView(EditText(this).apply {
            setText(initial)
            setSingleLine()
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = onText(s?.toString() ?: "")
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        scroll.addView(col)

        // Tab bar at the top: Overlay | Custom
        val customScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(buildCustomTab())
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val overlayTab = Button(this@MainActivity).apply { text = "Overlay" }
            val customTab = Button(this@MainActivity).apply { text = "Custom" }
            overlayTab.setOnClickListener {
                scroll.visibility = View.VISIBLE
                customScroll.visibility = View.GONE
            }
            customTab.setOnClickListener {
                scroll.visibility = View.GONE
                customScroll.visibility = View.VISIBLE
            }
            addView(overlayTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(customTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val frame = FrameLayout(this).apply {
            addView(scroll)
            addView(customScroll)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabs)
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        col.addView(TextView(this).apply { text = "QuestStats"; textSize = 26f; setTypeface(null, Typeface.BOLD) })

        // ---- Controls ----
        startBtn = Button(this).apply {
            text = "Start overlay"
            setOnClickListener {
                if (SharedState.running) {
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                    SharedState.running = false
                } else {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"))
                        )
                    } else {
                        startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                        SharedState.running = true
                    }
                }
            }
        }
        col.addView(startBtn)
        col.addView(TextView(this).apply {
            text = "Changes to options apply the next time you start the overlay."
            textSize = 12f
        })

        live = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#2e7d32"))
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(live)

        // ---- Overlay options ----
        header("Overlay")
        spinner("Display mode", OverlayMode.values().map { it.label },
            Prefs.int(this, Prefs.K_MODE, OverlayMode.BOTH.ordinal)) { Prefs.setInt(this, Prefs.K_MODE, it) }
        spinner("Position", Corner.values().map { it.label },
            Prefs.int(this, Prefs.K_CORNER, Corner.TOP_LEFT.ordinal)) { Prefs.setInt(this, Prefs.K_CORNER, it) }
        val intervals = listOf(500, 1000, 2000)
        spinner("Update interval", intervals.map { "$it ms" },
            intervals.indexOf(Prefs.int(this, Prefs.K_INTERVAL, 1000)).coerceAtLeast(0)) {
            Prefs.setInt(this, Prefs.K_INTERVAL, intervals[it])
        }
        val rates = listOf(72, 90, 120)
        spinner("Target refresh rate (Hz)", rates.map { "$it Hz" },
            rates.indexOf(Prefs.int(this, Prefs.K_TARGET_FPS, 72)).coerceAtLeast(0)) {
            Prefs.setInt(this, Prefs.K_TARGET_FPS, rates[it])
        }
        col.addView(TextView(this).apply { text = "Background opacity"; setPadding(0, dp(8), 0, 0) })
        col.addView(SeekBar(this).apply {
            max = 100
            progress = Prefs.int(this@MainActivity, Prefs.K_OPACITY, 70)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    Prefs.setInt(this@MainActivity, Prefs.K_OPACITY, p.coerceAtLeast(10))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        textField("App to measure FPS for (package name, blank = auto)",
            Prefs.str(this, Prefs.K_TARGET_PKG, ""), false) { Prefs.setStr(this, Prefs.K_TARGET_PKG, it) }

        // ---- Metrics ----
        var lastGroup = ""
        for (m in Metric.values()) {
            if (m.group != lastGroup) { header(m.group); lastGroup = m.group }
            toggle(m.label, Prefs.metricOn(this, m)) { Prefs.setMetric(this, m, it) }
        }

        // ---- Predictions ----
        header("Predictions")
        toggle("Show predictions inside overlay", Prefs.bool(this, Prefs.K_SHOW_PRED_OVERLAY, true)) {
            Prefs.setBool(this, Prefs.K_SHOW_PRED_OVERLAY, it)
        }
        for (p in Prediction.values()) {
            toggle(p.label, Prefs.predOn(this, p)) { Prefs.setPred(this, p, it) }
        }
        textField("Thermal limit for forecast (°C)",
            Prefs.float(this, Prefs.K_TEMP_LIMIT, 43f).toString(), true) {
            it.toFloatOrNull()?.let { v -> Prefs.setFloat(this, Prefs.K_TEMP_LIMIT, v) }
        }

        // ---- Logging / sharing ----
        header("Logging & remote view")
        toggle("Log to CSV (Android/data/com.example.queststats/files)", Prefs.bool(this, Prefs.K_CSV, false)) {
            Prefs.setBool(this, Prefs.K_CSV, it)
        }
        toggle("Serve live stats on port 8765 (local network)", Prefs.bool(this, Prefs.K_SERVER, false)) {
            Prefs.setBool(this, Prefs.K_SERVER, it)
        }
    }

    /** Builds the "Custom" tab. Uses its own column, then restores the settings column. */
    private fun buildCustomTab(): View {
        val settingsCol = col
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        val customCol = col
        col.addView(TextView(this).apply { text = "Custom"; textSize = 26f; setTypeface(null, Typeface.BOLD) })
        header("Custom FPS display")
        col.addView(TextView(this).apply {
            text = "Sets the FPS number shown on the overlay. This is display only: your actual " +
                "frame rate is not changed, and predictions and CSV logs keep using the real value."
            textSize = 13f
        })

        val status = TextView(this).apply { setPadding(0, dp(12), 0, dp(4)) }
        fun refreshStatus() {
            status.text = if (Prefs.bool(this, Prefs.K_CUSTOM_FPS_ON, false))
                "Custom FPS ON: showing ${Prefs.float(this, Prefs.K_CUSTOM_FPS, 72f)}"
            else "Custom FPS OFF: showing real FPS"
        }

        var pending = Prefs.float(this, Prefs.K_CUSTOM_FPS, 72f).toString()
        textField("FPS to display", pending, true) { pending = it }

        col.addView(Button(this).apply {
            text = "Apply custom FPS"
            setOnClickListener {
                val v = pending.toFloatOrNull()
                if (v != null && v > 0f && v <= 999f) {
                    Prefs.setFloat(this@MainActivity, Prefs.K_CUSTOM_FPS, v)
                    Prefs.setBool(this@MainActivity, Prefs.K_CUSTOM_FPS_ON, true)
                } else {
                    status.text = "Enter a number between 1 and 999"
                    return@setOnClickListener
                }
                refreshStatus()
            }
        })
        col.addView(Button(this).apply {
            text = "Turn off custom FPS"
            setOnClickListener {
                Prefs.setBool(this@MainActivity, Prefs.K_CUSTOM_FPS_ON, false)
                refreshStatus()
            }
        })
        col.addView(status)
        col.addView(TextView(this).apply {
            text = "Takes effect immediately while the overlay is running."
            textSize = 12f
        })
        refreshStatus()
        col = settingsCol
        return customCol
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }
}
