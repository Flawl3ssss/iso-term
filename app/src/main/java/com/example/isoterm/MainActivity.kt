package com.example.isoterm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.termux.view.TerminalView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

class MainActivity : AppCompatActivity(), TerminalSessionClient {

    private lateinit var termView: TerminalView
    private lateinit var logView: TextView
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI кодом, без XML — меньше файлов, проще сборка
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        termView = TerminalView(this, null)
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

        // Уведомления нужны для ForegroundService на 13+
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        // Фон сразу — иначе сессию прибьют при сворачивании
        TerminalService.start(this)
        log("Фон запущен. Xiaomi: дай автозапуск + без ограничений, иначе HyperOS убьёт даже FGS.")
        log("Изоляция: только ${filesDir.absolutePath}, sdcard/storage не монтируются.")
        if (ProotManager.isInstalled(this)) log("Ubuntu уже установлен. Жми Запустить.")
        else log("Жми Установить Ubuntu (качнёт ~30МБ ubuntu-base noble arm64).")
    }

    private fun log(s: String) {
        runOnUiThread { logView.append(s + "\n") }
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
                val code = ProotManager.unpackRootfs(tarGz, ProotManager.rootfsDir(this), ::logSync)
                log("tar exit=$code")
                // tar.gz можно удалить для экономии места
                // tarGz.delete()
                log(if (code == 0) "Готово. Жми Запустить." else "Ошибка распаковки, смотри лог выше.")
            } catch (e: Exception) {
                log("Ошибка: ${e.message}")
            }
        }.start()
    }

    private fun logSync(s: String) {
        runOnUiThread { logView.append(s + "\n") }
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
                    termView.setTerminalViewClient(object : com.termux.view.TerminalViewClient {
                        override fun onScale(scale: Float): Float = 1f
                        override fun onSingleTapUp(e: android.view.MotionEvent?) {}
                        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                        override fun shouldEnforceCharBasedInput(): Boolean = true
                        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                        override fun isTerminalViewSelected(): Boolean = true
                        override fun copyModeChanged(copyMode: Boolean) {}
                        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, s: TerminalSession?): Boolean = false
                        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
                        override fun onLongPress(e: android.view.MotionEvent?): Boolean = false
                    })
                    // Фон держать пока открыта сессия
                    TerminalService.start(this)
                    log("Сессия запущена: proot -r ubuntu + только внутренние бинды.")
                }
            } catch (e: Exception) {
                log("Запуск упал: ${e.message}")
            }
        }.start()
    }

    // --- TerminalSessionClient ---
    override fun onTextChanged(s: TerminalSession?) {}
    override fun onTitleChanged(s: TerminalSession?) {}
    override fun onSessionFinished(s: TerminalSession?) { log("Сессия завершена.") }
    override fun onCopyTextToClipboard(s: TerminalSession?, text: String?) {
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("term", text))
    }
    override fun onPasteTextFromClipboard(s: TerminalSession?) {}
    override fun onBell(s: TerminalSession?) {}
    override fun onColorsChanged(s: TerminalSession?) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
}
