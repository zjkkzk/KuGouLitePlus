package com.bldf1.kugoulite.plus

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * R331 诊断：二级页"白色圆角底板"与 View 树无关（12 次实验排除完毕），
 * 只能来自 Canvas 直接绘制。hook Canvas.drawRoundRect（4 参重载）：
 *   ① rect 覆盖底栏（y 2400~2712）且宽度 ≥800（全宽圆角）
 *   ② Paint 是不透明浅色（白/近白）
 * ⇒ 打印 Paint 参数 + 调用栈（栈里就是宿主的类名/方法名）。
 * 命中 1 次后自动停止（diagFired），避免刷爆日志。
 */
object CanvasDiag {

    @Volatile private var diagFired = false

    private const val BAR_TOP = 2400
    private const val BAR_BOTTOM = 2712
    private const val MIN_WIDTH = 800

    fun install(xposed: XposedModule) {
        try {
            // R331b：同时 hook 两个重载 —— 4 参(RectF,...) 和 5 参(float,l,t,r,b,...)
            val targets = Canvas::class.java.declaredMethods.filter {
                it.name == "drawRoundRect" && it.parameterTypes.last() == Paint::class.java
            }
            ModuleLog.log("CanvasDiag 找到 drawRoundRect 重载: " + targets.joinToString { m -> "(${m.parameterTypes.size}参)" })
            if (targets.isEmpty()) {
                ModuleLog.log("CanvasDiag: 未找到 drawRoundRect 重载")
                return
            }
            for (m in targets) xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        if (diagFired) return@intercept chain.proceed()
                        val r: RectF
                        val paint: Paint
                        if (chain.args[0] is RectF) {
                            r = chain.args[0] as RectF
                            paint = chain.args[3] as Paint
                        } else {
                            r = RectF(
                                chain.args[0] as Float, chain.args[1] as Float,
                                chain.args[2] as Float, chain.args[3] as Float
                            )
                            paint = chain.args[4] as Paint
                        }
                        if (r.width() < MIN_WIDTH) return@intercept chain.proceed()
                        if (r.bottom < BAR_TOP || r.top > BAR_BOTTOM) return@intercept chain.proceed()
                        val c = paint.color
                        val a = (c ushr 24) and 0xff
                        val rr = (c shr 16) and 0xff
                        val gg = (c shr 8) and 0xff
                        val bb = c and 0xff
                        if (a < 180) return@intercept chain.proceed()
                        if (rr < 200 || gg < 200 || bb < 200) return@intercept chain.proceed()
                        diagFired = true
                        val sb = StringBuilder()
                        sb.append("BARDIAG 命中白底绘制: rect=").append(r)
                            .append(" color=#").append(Integer.toHexString(c))
                            .append(" style=").append(paint.style)
                            .append(" shader=").append(paint.shader != null)
                            .append('\n')
                        val st = Thread.currentThread().stackTrace
                        for (i in 0 until minOf(st.size, 16)) {
                            sb.append("BARDIAG   #").append(i).append(' ').append(st[i]).append('\n')
                        }
                        ModuleLog.log(sb.toString())
                    } catch (_: Throwable) {
                    }
                    chain.proceed()
                }
            ModuleLog.log("CanvasDiag 已安装（drawRoundRect hook，一次性）")
        } catch (t: Throwable) {
            ModuleLog.log("CanvasDiag 安装失败: " + t.javaClass.simpleName + ": " + t.message)
        }
    }
}
