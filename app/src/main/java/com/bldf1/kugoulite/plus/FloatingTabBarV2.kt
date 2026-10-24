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

    // ⭐ R288：按宿主 tab 的真实位置重新对齐（用户反馈"底栏太低、图标没居中"）。
    //
    // 实测 5.3.1 底栏几何（density=3.0，fv5 = 2442~2712，高 270px）：
    //   发现图标 at1   y 2478~2553
    //   发现文字 at2   y 2562~2599
    //   ⇒ tab 内容区间 y 2478~2599（高 121px），垂直中心 ≈ 2538
    //
    // 胶囊要求：包住 2478~2599 且上下留白均匀 ⇒ y 2466~2610（高 144px），
    // 中心 2538 = tab 中心 ⇒ 图标视觉居中。
    // 相对 fv5 底部（2712）：bottomMargin = 2712 - 2610 = 102px = 34dp
    // ⭐ R289：胶囊必须包住**最高的子元素**。
    // 5.3.1 底栏里三个元素的实测高度：
    //   发现 tab（at1 图标 2478~2553 + at2 文字 2562~2599）  121px
    //   播放钮（fke 2457~2613）                             156px  ← 最高
    //   我的 tab（at4 + at5）                               121px
    // 胶囊高度 144px < 156px ⇒ 播放钮上下被裁（用户反馈"图标没居中"）。
    // 改为 168px（156 + 上下各 6px 留白），底边贴 2613：
    //   bottomMargin = 2712 - 2613 = 99px = 33dp
    //   胶囊 y 2445~2613，中心 2529 ≈ 播放钮中心 2535 ✓
    // ⭐ R290：**回到旧版（5.3.0）的胶囊尺寸**。用户要对比，对比结果：
    //              旧版            新版(我上一版)     差
    //   左右边距    16dp = 48px     16dp = 48px       同
    //   高度        62dp = 186px    56dp = 168px      矮 18px  ← 问题
    //   圆角        高度一半(93px)   28dp = 84px       略小
    //   垂直定位    top 6dp(从栏顶)  bottom 33dp(从栏底)
    // 旧版 186px 包住 156px 的播放钮，**上下各留 15px**；
    // 我上一版只有 168px，上下各 6px ⇒ 太紧，视觉上图标没居中。
    // 现在统一回 62dp，并按旧版的 top 基准换算 bottomMargin：
    //   fv5 高 270px，胶囊 186px，顶部留 18px(6dp) ⇒ 底部留 270-186-18 = 66px = 22dp
    /** 胶囊高度（dp）→ 186px，与旧版一致 */
    private const val HEIGHT_DP = 62f

    // 垂直基准换成"胶囊中心 = 播放钮中心"（比旧版的 top 基准更准）：
    //   播放钮 fke y 2457~2613 ⇒ 中心 2535
    //   胶囊 186px ⇒ y 2442~2628（中心 2535）
    //   bottomMargin = 2712 - 2628 = 84px = 28dp
    /** 距底栏底部（dp）→ 84px，让胶囊中心与播放钮中心对齐 */
    private const val BOTTOM_DP = 28f

    /** 圆角（dp）→ 31dp = 93px = 高度一半，完整药丸形（与旧版一致） */
    private const val RADIUS_DP = 31f

    private var overlay: View? = null
    private var hostRef: WeakReference<View>? = null
    private val bgRefs = ArrayList<WeakReference<View>>()
    private var appliedActivity: WeakReference<Activity>? = null

    fun isApplied(): Boolean = overlay?.parent != null

    /**
     * 确保胶囊 overlay 已挂载。**只读宿主几何，不改布局属性。**
     * @return true = 已挂载或本次挂载成功
     */
    fun ensure(activity: Activity, decor: View): Boolean {
        if (isApplied()) {
            // 同一个 Activity 直接复用；换了 Activity 要重建
            if (appliedActivity?.get() === activity) {
                ModuleLog.log("V2诊断: 已挂载且同 Activity，跳过")
                return true
            }
            remove()
        }
        val bar = findHostBar(decor)
        if (bar == null) {
            ModuleLog.log("V2诊断: findHostBar 返回 null（fv5/fv2 都没找到，或尺寸为0）")
            return false
        }
        if (bar.getWidth() <= 0 || bar.getHeight() <= 0) {
            ModuleLog.log("V2诊断: 底栏尺寸为 0（${bar.getWidth()}x${bar.getHeight()}）")
            return false
        }
        ModuleLog.log("V2诊断: 找到底栏 ${bar.javaClass.simpleName} ${bar.getWidth()}x${bar.getHeight()}")

        val dm = activity.resources.displayMetrics
        val density = dm.density
        val side = (SIDE_DP * density).toInt()
        val height = (HEIGHT_DP * density).toInt()
        val bottom = (BOTTOM_DP * density).toInt()
        val radius = (RADIUS_DP * density).toFloat()

        // ---- 1) 构建胶囊 overlay（先挂，成功后才隐藏宿主）----
        // 顺序很关键：如果先 alpha=0 再 addView 失败，就会出现「底栏没了、胶囊也没挂上」
        // 的黑洞状态（本轮实测踩到过）。
        // ⭐⭐ 关键（实测 decor 层级）：
        //   DecorView
        //     └─ LinearLayout
        //          ├─ ViewStub#action_mode_bar_stub
        //          └─ FrameLayout#content   ← 业务内容在这里
        // 把 overlay 加到 **DecorView** 上会被忽略（它有自己的布局逻辑，只认那个
        // LinearLayout），这正是"日志说挂载成功、画面上看不见"的原因。
        // 必须加到 android.R.id.content 里，并且 bringToFront 到最上层。
        // ⭐⭐ R284（方案 A）：胶囊挂进**宿主底栏本体 fv5** 的最底层（index=0），
        // 只把它的**背景层**透明掉，tab 图标/文字/播放钮全部保留原样。
        // 这样：
        //   · 胶囊在 asz(tab) 和 fke(播放钮) **之下** ⇒ 不会盖住内容
        //   · fki/fkj(背景层) alpha=0 ⇒ 不再遮挡胶囊
        //   · 原生 tab 的点击、选中态、动画全部不受影响
        val root: ViewGroup = (bar as? ViewGroup) ?: run {
            ModuleLog.log("V2诊断: 底栏不是 ViewGroup，无法插入胶囊")
            return false
        }
        ModuleLog.log("V2诊断: root=fv5 ${root.javaClass.simpleName} children=${root.childCount}")
        val card = FrameLayout(activity)
        card.isClickable = false
        card.isFocusable = false
        // 不拦截触摸：所有事件继续落到下面的宿主底栏上
        card.setOnTouchListener { _, _ -> false }

        val dark = isDark(activity)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (dark) 0xFF2A2A32.toInt() else 0xFFFFFFFF.toInt())
        }
        card.background = bg

        // 悬浮感靠阴影：单纯改颜色的话，浅色模式和宿主背景区分不出来
        //（本轮踩过：先用 #F7F7FA 结果和背景几乎一样，误判成"没画出来"）
        card.elevation = 10f * density
        card.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        card.clipToOutline = true

        // ⭐ R285：fv5 是 **ConstraintLayout**，直接给 ViewGroup.LayoutParams 不生效
        //（实测：胶囊被甩到顶部）。用一个 FrameLayout 全屏包装，内部再用
        // FrameLayout.LayoutParams 的 bottomMargin 定位，避免引入 constraintlayout 依赖。
        val wrap = FrameLayout(activity)
        wrap.isClickable = false
        wrap.isFocusable = false
        wrap.setOnTouchListener { _, _ -> false }
        wrap.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                height,
            ).apply {
                leftMargin = side
                rightMargin = side
                bottomMargin = bottom
                gravity = android.view.Gravity.BOTTOM
            },
        )
        // 包装层插在最底层（index=0），tab 和播放钮自然盖在它上面
        root.addView(wrap, 0, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        // 必须提到最上层：decor 里通常有 content 等业务容器，
        // 单纯 addView 会被后面的兄弟节点盖住（本轮实测：日志说挂载成功但完全看不见）
        try { card.bringToFront() } catch (_: Throwable) {}
        overlay = card
        appliedActivity = WeakReference(activity)

        // ⭐⭐ R282：**先证明视图真的进了绘制树且可见**，再谈样式。
        // 前 6 轮失败的根本原因就是只用截图判断"有/没有"，从不验证视图状态。
        card.post {
            try {
                val rect = android.graphics.Rect()
                val visible = card.getGlobalVisibleRect(rect)
                ModuleLog.log(
                    "V2诊断: parent=${card.parent?.javaClass?.simpleName}" +
                    " attached=${card.isAttachedToWindow}" +
                    " size=${card.width}x${card.height}" +
                    " alpha=${card.alpha} vis=${card.visibility}" +
                    " globalVisible=$visible rect=$rect" +
                    " rootClass=${root.javaClass.simpleName} rootChildren=${root.childCount}" +
                    " cardIndex=${(0 until root.childCount).firstOrNull { root.getChildAt(it) === card }}"
                )
            } catch (_: Throwable) {
            }
        }

        // ---- 2) overlay 挂好之后，再隐藏宿主底栏（唯一对宿主的改动，幂等）----
        // 为什么用 alpha 而不是 INVISIBLE/GONE：
        //   · INVISIBLE 参与布局但会影响某些 Visibility 联动逻辑
        //   · GONE 会让宿主重新求解约束（历史上导致过白卡缩宽）
        //   · alpha 只影响绘制，不改测量/布局，View 被宿主复用后也不会留下几何痕迹
        // 只透明**背景层**，保留 tab / 播放钮
        try {
            for (bgId in arrayOf("fki", "fkj")) {
                val bgView = try {
                    val rid = decor.resources.getIdentifier(bgId, "id", decor.context.packageName)
                    if (rid != 0) decor.findViewById<View>(rid) else null
                } catch (_: Throwable) { null }
                if (bgView != null) {
                    bgView.alpha = 0f
                    bgRefs += WeakReference(bgView)
                }
            }
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
        for (r in bgRefs) {
            try { r.get()?.alpha = 1f } catch (_: Throwable) {}
        }
        bgRefs.clear()
        hostRef = null
        appliedActivity = null
    }

    /** ⭐ 诊断：打印 decor 层级，搞清楚 overlay 该加在哪一层（只跑一次） */
    fun diagHierarchy(decor: View) {
        try {
            val sb = StringBuilder()
            fun walk(v: View, d: Int) {
                if (d > 5) return
                val rid = try {
                    if (v.id == View.NO_ID) "-" else v.resources.getResourceEntryName(v.id)
                } catch (_: Throwable) { "?" }
                sb.append('\n').append("  ".repeat(d))
                    .append(v.javaClass.simpleName).append("#").append(rid)
                    .append(" ").append(v.width).append("x").append(v.height)
                    .append(" vis=").append(v.visibility)
                    .append(" alpha=").append(v.alpha)
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
                }
            }
            walk(decor, 0)
            ModuleLog.log("decor层级:" + sb.toString())
        } catch (_: Throwable) {
        }
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
