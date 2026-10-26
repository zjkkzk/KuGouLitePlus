package com.bldf1.kugoulite.plus

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * R332 诊断：hook View.draw(Canvas)（Java 方法，能抓到真正执行绘制的 View），
 * 在二级页时记录 3 秒内所有"宽>900、高150~350、屏幕 y>2200"的 View ——
 * uiautomator 看不到它们，但它们真的上屏了。一次看清白底是谁。
 */
object ViewDrawDiag {

    @Volatile private var recording = false
    @Volatile private var recorded = 0
    private var lastToggleAt = 0L

    /** UiCleaner 每次扫描调用：进入二级页时开始记录 3 秒 */
    fun tick(pageId: String) {
        try {
            val now = SystemClock.uptimeMillis()
            val isSub = pageId.startsWith("frag:") ||
                pageId in setOf("fav", "recent", "local", "bought", "profile")
            if (isSub && !recording && now - lastToggleAt > 10_000L) {
                recording = true
                recorded = 0
                lastToggleAt = now
                ModuleLog.log("VDIAG ===== 开始记录（pageId=" + pageId + "）=====")
                Handler(Looper.getMainLooper()).postDelayed({
                    recording = false
                    ModuleLog.log("VDIAG ===== 记录结束，共 " + recorded + " 条 =====")
                }, 3000)
            }
        } catch (_: Throwable) {}
    }

    fun install(xposed: XposedModule) {
        try {
            val m = View::class.java.getDeclaredMethod(
                "draw", android.graphics.Canvas::class.java
            )
            m.isAccessible = true
            xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        if (!recording || recorded >= 12) return@intercept chain.proceed()
                        val v = chain.thisObject as? View ?: return@intercept chain.proceed()
                        val w = v.width
                        val h = v.height
                        if (w > 300 && h > 50) {
                            val loc = IntArray(2)
                            v.getLocationOnScreen(loc)
                            if (loc[1] > 2200) {
                                recorded++
                                val bg = try {
                                    v.background?.javaClass?.simpleName ?: "-"
                                } catch (_: Throwable) { "?" }
                                ModuleLog.log(
                                    "VDIAG #" + recorded + " " + (try { (v.tag as? String) ?: "-" } catch (_: Throwable) { "-" }) +
                                        " " + v.javaClass.simpleName + " " + w + "x" + h +
                                        " screenY=" + loc[1] + " vis=" + v.visibility +
                                        " alpha=" + v.alpha + " bg=" + bg
                                )
                            }
                        }
                    } catch (_: Throwable) {}
                    chain.proceed()
                }
            ModuleLog.log("ViewDrawDiag 已安装（View.draw hook）")
        } catch (t: Throwable) {
            ModuleLog.log("ViewDrawDiag 安装失败: " + t.javaClass.simpleName + ": " + t.message)
        }
    }
}
