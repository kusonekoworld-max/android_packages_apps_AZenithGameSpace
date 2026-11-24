package zx.azenith.ui.util

import com.topjohnwu.superuser.Shell

/**
 * The root build of AZenith called `sys.azenith-service -p / -bpl / -cbc / -tb / -rp`
 * and `sys.azenith-utilityconf checkmalipath` from a root shell. On a ROM without root
 * the same work is done by the integrated daemon, which the app reaches over binder
 * (IAZenithService v5: startTask / getTaskOutput).
 *
 * [Shell] hands every script to [tryHandle] first, so the many call sites that still
 * spell the old command keep working unchanged and still get the live output they
 * parse (`Found working node:`, `Node is compatible:`, the `[FOUND]` rows, ...).
 */
internal object DaemonBridge {
    private const val POLL_MS = 250L
    private const val TIMEOUT_MS = 20 * 60 * 1000L

    private val profileCmd = Regex("""^\S*sys\.azenith-service\s+(?:-p|--profile)\s+(\d)$""")
    private val listCmd = Regex("""^\S*sys\.azenith-service\s+(?:-bpl|--bypasspathlist)$""")
    private val checkCmd = Regex("""^\S*sys\.azenith-service\s+(?:-cbc|--checkbypasschg)$""")
    private val testCmd = Regex("""^\S*sys\.azenith-service\s+(?:-tb|--testbypass)\s+(.+)$""")
    private val restoreCmd = Regex("""^\S*sys\.azenith-service\s+(?:-rp|--restorepending)$""")
    private val maliCmd = Regex("""^\S*sys\.azenith-utilityconf\s+checkmalipath$""")

    /** `null` when [script] is not one of the legacy daemon commands. */
    fun tryHandle(script: String, sink: MutableList<String>?): Shell.Result? {
        val cmd = script.trim().removeSuffix("2>&1").trim()

        profileCmd.matchEntire(cmd)?.let { return profile(it.groupValues[1].toInt()) }
        if (listCmd.matches(cmd)) return task("bypass_list", "", sink)
        if (checkCmd.matches(cmd)) return task("bypass_check", "", sink)
        testCmd.matchEntire(cmd)?.let {
            val node = it.groupValues[1].trim().trim('"', '\'').removePrefix("--path=")
            return task("bypass_test", node, sink)
        }
        if (restoreCmd.matches(cmd)) return task("bypass_restore", "", sink)
        if (maliCmd.matches(cmd)) return task("mali_check", "", sink)
        return null
    }

    private fun profile(mode: Int): Shell.Result {
        if (mode !in 1..3) return Shell.Result(emptyList(), listOf("Invalid profiles."), 1)
        return if (AZenithControl.applyProfile(mode.toString())) {
            Shell.Result(listOf("Applying profile $mode"), emptyList(), 0)
        } else {
            Shell.Result(
                emptyList(),
                listOf("ERROR: Auto Mode is enabled or the daemon is not running. Manual profile selection is blocked."),
                1
            )
        }
    }

    private fun task(name: String, arg: String, sink: MutableList<String>?): Shell.Result {
        if (!AZenithControl.supportsTasks()) {
            val msg = "AZenith daemon is not running or too old (needs interface v5). Rebuild vendor.azenith-service."
            sink?.add(msg)
            return Shell.Result(emptyList(), listOf(msg), -1)
        }
        if (!AZenithControl.startTask(name, arg)) {
            // Same task still running (screen reopened mid-scan): attach to its output.
            if (!AZenithControl.taskOutput(name).let { it.isNotEmpty() && !it.contains("##DONE") }) {
                val msg = "Daemon refused task $name"
                sink?.add(msg)
                return Shell.Result(emptyList(), listOf(msg), 1)
            }
        }

        val out = ArrayList<String>()
        var emitted = 0
        var code = -1
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // Only whole lines: the element after the last "\n" is a partial tail (or "").
            val complete = AZenithControl.taskOutput(name).split("\n").dropLast(1)
            var done = false
            for (i in emitted until complete.size) {
                val line = complete[i]
                if (line.startsWith("##DONE")) {
                    code = line.removePrefix("##DONE").trim().toIntOrNull() ?: 0
                    done = true
                    break
                }
                out.add(line)
                sink?.add(line)
                emitted = i + 1
            }
            if (done) break
            try { Thread.sleep(POLL_MS) } catch (e: InterruptedException) { break }
        }
        return Shell.Result(out, emptyList(), code)
    }
}
