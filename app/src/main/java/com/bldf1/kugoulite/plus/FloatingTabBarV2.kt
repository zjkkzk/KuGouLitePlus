package com.bldf1.kugoulite.plus

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * 悬浮底栏 v2 —— **独立 overlay 方案**（R279 重写，替代 FloatingTabBar 的改造方案）。
 *
 * ## 为什么重写
 * 旧方案在**宿主底栏上直接改**（LayoutParams / visibility / background / 列表 clipToPadding），
 * 前提是"改完还能还原"。但 5.3.1 的宿主把**同一个 View 实例**在「底栏」和「迷你播放条」
 * 之间复用 —— 改造结果会残留到复用后的形态上，把迷你播放条改坏（用户实锤）。
 * 而且**任何验证都挡不住**（同 id、同高度），因为认的就是同一个对象。
 *
 * ## 新方案的核心原则
 * **一个字节都不改宿主底栏的几何/布局属性。**
 * ```
 * decor
 *  ├─ 宿主底栏（几何原样，只把 alpha 设 0）
 *  └─ 我们的胶囊 overlay（盖在它上面）
 * ```
 * - 位置尺寸：只**读** `bar.getLocationInWindow` / `width` / `height`
 * - 四角处理（用户选 A）：宿主底栏 `alpha = 0`（幂等，不残留几何信息）
 * - 切页：只 `removeView(overlay)` + 宿主 `alpha = 1`（幂等复原）
 * - 页面限制：只在 `curPageId ∈ {main, discovery}` 时挂载
 *
 * ## 第一版范围（用户明确）
 * **只做胶囊形状 + 底栏颜色，不做液态玻璃。** 跑通再迭代。
 */
internal object FloatingTabBarV2 {

    private const val TAG = "FloatingBarV2"

    /** 5.3.1 的底栏本体 */
    private val BAR_IDS = arrayOf("fv5", "fv2")

    /** 胶囊左右边距（px，1080 基准按 density 缩放） */
    private const val SIDE_DP = 16f

    /** 胶囊高度（dp） */
    private const val HEIGHT_DP = 62f

    /** 距屏幕底部（dp） */
    private const val BOTTOM_DP = 10f

    /** 圆角（dp） */
    private const val RADIUS_DP = 31f

    private var overlay: View? = null
    private var hostRef: WeakReference<View>? = null
    private var appliedActivity: WeakReference<Activity>? = null

    fun isApplied(): Boolean = overlay?.parent != null

    /**
     * 确保胶囊 overlay 已挂载。**只读宿主几何，不改布局属性。**
     * @return true = 已挂载或本次挂载成功
     */
    fun ensure(activity: Activity, decor: View): Boolean {
        if (isApplied()) {
            // 同一个 Activity 直接复用；换了 Activity 要重建
            if (appliedActivity?.get() === activity) return true
            remove()
        }
        val bar = findHostBar(decor) ?: return false
        if (bar.getWidth() <= 0 || bar.getHeight() <= 0) return false

        val dm = activity.resources.displayMetrics
        val density = dm.density
        val side = (SIDE_DP * density).toInt()
        val height = (HEIGHT_DP * density).toInt()
        val bottom = (BOTTOM_DP * density).toInt()
        val radius = (RADIUS_DP * density).toFloat()

        // ---- 1) 构建胶囊 overlay（先挂，成功后才隐藏宿主）----
        // 顺序很关键：如果先 alpha=0 再 addView 失败，就会出现「底栏没了、胶囊也没挂上」
        // 的黑洞状态（本轮实测踩到过）。
        val root: ViewGroup = (decor as? ViewGroup)
            ?: (decor.parent as? ViewGroup)
            ?: (decor.rootView as? ViewGroup)
            ?: return false
        val card = FrameLayout(activity)
        card.isClickable = false
        card.isFocusable = false
        // 不拦截触摸：所有事件继续落到下面的宿主底栏上
        card.setOnTouchListener { _, _ -> false }

        val dark = isDark(activity)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (dark) 0xFF23232A.toInt() else 0xFFF7F7FA.toInt())
        }
        card.background = bg

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            height,
        ).apply {
            leftMargin = side
            rightMargin = side
            bottomMargin = bottom
            gravity = android.view.Gravity.BOTTOM
        }
        root.addView(card, lp)
        // 必须提到最上层：decor 里通常有 content 等业务容器，
        // 单纯 addView 会被后面的兄弟节点盖住（本轮实测：日志说挂载成功但完全看不见）
        try { card.bringToFront() } catch (_: Throwable) {}
        overlay = card
        appliedActivity = WeakReference(activity)

        // ---- 2) overlay 挂好之后，再隐藏宿主底栏（唯一对宿主的改动，幂等）----
        // 为什么用 alpha 而不是 INVISIBLE/GONE：
        //   · INVISIBLE 参与布局但会影响某些 Visibility 联动逻辑
        //   · GONE 会让宿主重新求解约束（历史上导致过白卡缩宽）
        //   · alpha 只影响绘制，不改测量/布局，View 被宿主复用后也不会留下几何痕迹
        try {
            bar.alpha = 0f
            hostRef = WeakReference(bar)
        } catch (_: Throwable) {
        }
        ModuleLog.log("悬浮底栏v2: 胶囊已挂载 (side=${side}px h=${height}px bottom=${bottom}px) 宿主几何未改")
        return true
    }

    /** 摘除 overlay 并把宿主 alpha 复原（幂等，任何时候调用都安全） */
    fun remove() {
        val ov = overlay
        overlay = null
        if (ov != null) {
            try {
                (ov.parent as? ViewGroup)?.removeView(ov)
            } catch (_: Throwable) {
            }
        }
        // alpha=1 是宿主的默认值，无论这个 View 现在被复用成什么，复原都是安全的
        try {
            hostRef?.get()?.alpha = 1f
        } catch (_: Throwable) {
        }
        hostRef = null
        appliedActivity = null
    }

    private fun findHostBar(decor: View): View? {
        for (id in BAR_IDS) {
            val v: View? = try {
                val rid = decor.resources.getIdentifier(id, "id", decor.context.packageName)
                if (rid != 0) decor.findViewById<View>(rid) else null
            } catch (_: Throwable) {
                null
            }
            if (v != null && v.getWidth() > 0 && v.getHeight() > 0) return v
        }
        return null
    }

    private fun isDark(context: Context): Boolean {
        return try {
            val mode = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } catch (_: Throwable) {
            false
        }
    }
}
