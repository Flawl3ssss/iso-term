package com.example.isoterm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

class MainActivity : AppCompatActivity(), TerminalSessionClient {

    private lateinit var termView: TerminalView
    private lateinit var logView: TextView
    private var session: TerminalSession? = null
    private val viewClient = IsoViewClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        termView = TerminalView(this, null)
        termView.setTerminalViewClient(viewClient)
        termView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )

        logView = TextView(this).apply { textSize = 12f }
        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300
            )
            addView(logView)
        }

        fun btn(text: String, fn: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { fn() }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Установить Ubuntu") { installUbuntu() })
            addView(btn("Запустить") { launchShell() })
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Батарея: без ограничений") {
                XiaomiHelper.requestIgnoreBattery(this@MainActivity)
            })
            addView(btn("Xiaomi автозапуск") {
                XiaomiHelper.openAutostart(this@MainActivity)
                toast("Включи автозапуск + Батарея/Без ограничений + замок в Recents")
            })
        }
        val wakeSwitch = Switch(this).apply {
            text = "WakeLock (держать CPU)"
            isChecked = TerminalService.holdWake
            setOnCheckedChangeListener { _, on -> TerminalService.holdWake = on }
        }
        val autoSwitch = Switch(this).apply {
            text = "Рестарт после ребута"
            val p = getSharedPreferences("iso", MODE_PRIVATE)
            isChecked = p.getBoolean("autostart", false)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("autostart", on).apply()
            }
        }

        root.addView(termView)
        root.addView(row1)
        root.addView(row2)
        root.addView(wakeSwitch)
        root.addView(autoSwitch)
        root.addView(logScroll)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        TerminalService.start(this)
        log("Фон запущен. Xiaomi: дай автозапуск + без ограничений, иначе HyperOS убьёт даже FGS.")
        log("Изоляция: только ${filesDir.absolutePath}, sdcard/storage не монтируются.")
        if (ProotManager.isInstalled(this)) log("Ubuntu уже установлен. Жми Запустить.")
        else log("Жми Установить Ubuntu (качнёт ~30МБ ubuntu-base noble arm64).")
    }

    private fun log(s: String) {
        Log.i("IsoTerm", s)
        runOnUiThread { try { logView.append(s + "\n") } catch (_: Exception) {} }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun installUbuntu() {
        log("Установка: качаю proot + ubuntu-base...")
        Thread {
            try {
                val proot = ProotManager.ensureProot(this)
                log("proot: $proot")
                val tarGz = File(filesDir, "ubuntu-base.tar.gz")
                if (!tarGz.exists() || tarGz.length() < 10_000_000) {
                    log("Качаю " + ProotManager.UBUNTU_URL)
                    ProotManager.downloadToFile(ProotManager.UBUNTU_URL, tarGz)
                }
                log("Распаковка в " + ProotManager.rootfsDir(this))
                val code = ProotManager.unpackRootfs(tarGz, ProotManager.rootfsDir(this)) { log(it) }
                log("tar exit=$code")
                log(if (code == 0) "Готово. Жми Запустить." else "Ошибка распаковки, смотри лог выше.")
            } catch (e: Exception) {
                log("Ошибка: ${e.message}")
            }
        }.start()
    }

    private fun launchShell() {
        Thread {
            try {
                if (!ProotManager.isInstalled(this)) {
                    log("Сначала Установить Ubuntu")
                    return@Thread
                }
                val proot = ProotManager.ensureProot(this).absolutePath
                val argv = ProotManager.buildCmd(this, proot)
                val env = ProotManager.buildEnv(this)
                val cwd = ProotManager.homeDir(this).absolutePath
                runOnUiThread {
                    session = TerminalSession(
                        proot, cwd, argv, env,
                        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                        this
                    )
                    termView.attachSession(session)
                    TerminalService.start(this)
                    log("Сессия запущена: proot -r ubuntu + только внутренние бинды.")
                }
            } catch (e: Exception) {
                log("Запуск упал: ${e.message}")
            }
        }.start()
    }

    // --- TerminalSessionClient (сигнатуры как в termux 0.118.0) ---
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { log("Сессия завершена.") }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        try {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("term", text))
        } catch (_: Exception) {}
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }

    inner class IsoViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1f
        override fun onSingleTapUp(e: MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
    }
}
