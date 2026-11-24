package com.topjohnwu.superuser

import java.util.concurrent.Executors

abstract class CallbackList<E> : AbstractMutableList<E>() {
    abstract fun onAddElement(e: E)
    override val size: Int get() = 0
    override fun get(index: Int): E = throw IndexOutOfBoundsException()
    override fun add(index: Int, element: E) { onAddElement(element) }
    override fun removeAt(index: Int): E = throw UnsupportedOperationException()
    override fun set(index: Int, element: E): E = throw UnsupportedOperationException()
}

object Shell {
    private val pool = Executors.newCachedThreadPool()

    class Result internal constructor(
        val out: List<String>,
        val err: List<String>,
        val code: Int
    ) {
        val isSuccess: Boolean get() = code == 0
    }

    class Job internal constructor(private val cmds: Array<out String>) {
        private var sink: MutableList<String>? = null

        fun to(list: MutableList<String>): Job { sink = list; return this }

        fun exec(): Result {
            // Legacy `sys.azenith-service -bpl/-cbc/-tb/-rp/-p` calls go to the integrated daemon over binder.
            zx.azenith.ui.util.DaemonBridge.tryHandle(cmds.joinToString("\n"), sink)?.let { return it }
            val out = ArrayList<String>()
            val err = ArrayList<String>()
            return try {
                val p = ProcessBuilder("sh", "-c", cmds.joinToString("\n")).start()
                p.outputStream.close()
                val t = Thread { p.errorStream.bufferedReader().forEachLine { err.add(it) } }
                t.start()
                p.inputStream.bufferedReader().forEachLine { out.add(it); sink?.add(it) }
                t.join()
                Result(out, err, p.waitFor())
            } catch (e: Exception) {
                Result(out, err, -1)
            }
        }

        fun submit(callback: ((Result) -> Unit)? = null) {
            pool.execute {
                val r = exec()
                if (callback != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { callback(r) }
                }
            }
        }
    }

    class Instance internal constructor() {
        val isRoot: Boolean get() = true
    }

    @JvmStatic fun cmd(vararg commands: String): Job = Job(commands)
    @JvmStatic fun getShell(): Instance = Instance()
    @JvmStatic fun getCachedShell(): Instance? = null
    @JvmStatic fun isAppGrantedRoot(): Boolean? = true
}
