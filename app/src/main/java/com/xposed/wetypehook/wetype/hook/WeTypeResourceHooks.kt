package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.VectorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.findMethod
import com.xposed.wetypehook.xposed.findMethodInHierarchy
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.hookReturnConstant
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.graphics.SfSymbols
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroup
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorMode
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.wetype.settings.CANDIDATE_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.ICON_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.KEY_POPUP_BG_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.KEY_TEXT_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal object WeTypeResourceHooks {
    private const val WETYPE_RESOURCE_PACKAGE = "com.tencent.wetype"
    private const val WETYPE_ATTR_R_CLASS = "com.tencent.wetype.plugin.hld.o"
    private const val WETYPE_COLOR_R_CLASS = "com.tencent.wetype.plugin.hld.p"
    private const val WETYPE_DRAWABLE_R_CLASS = "com.tencent.wetype.plugin.hld.r"
    private const val WETYPE_ID_R_CLASS = "com.tencent.wetype.plugin.hld.s"
    private const val KEY_DATA_CLASS =
        "com.tencent.wetype.plugin.hld.keyboard.selfdraw.bean.KeyData"
    // Faint hairline alpha applied to the host key border color (mirrors the legacy flat look).
    private const val KEY_BORDER_ALPHA = 0x20

    private const val LOGO_RESOURCE_TAG_KEY = 0x4D49554C
    private const val CANDIDATE_SELF_VIEW_PACKAGE =
        "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview."
    private const val CANDIDATE_SCROLL_VIEW_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.selfdraw.scrollview.SelfDrawScrollView"
    private const val CANDIDATE_VIEW_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
    private const val CANDIDATE_PINYIN_CONTAINER_ID_NAME = "strike_container_rl"
    private val typedArrayAttributeCache = Collections.synchronizedMap(
        WeakHashMap<TypedArray, IntArray>()
    )

    /**
     * 运行期登记的「颜色资源 id → 颜色组」覆盖表。自绘键盘两步取色:先 resolveAttribute
     * 拿到颜色资源 id,再单独 getColor 该 id——第一步的 TypedValue 改写拦不住第二步,
     * 所以 attr(Text 角色)命中时把解析出的资源 id 登记进来,getColor 家族查表即替换。
     * 以资源 id 为键,与插件混淆资源名解耦。
     */
    private val runtimeColorOverrides =
        java.util.concurrent.ConcurrentHashMap<Int, WeTypeAppearanceColorGroup>()
    private val resourcePackageCache = Collections.synchronizedMap(
        WeakHashMap<Resources, MutableMap<Int, Boolean>>()
    )
    private data class CandidatePinyinListeners(
        val layout: View.OnLayoutChangeListener,
        val attach: View.OnAttachStateChangeListener
    )

    private data class ViewPadding(
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int
    )

    private val candidatePinyinMarginListeners = Collections.synchronizedMap(
        WeakHashMap<View, CandidatePinyinListeners>()
    )
    private val candidatePinyinOriginalPaddings = Collections.synchronizedMap(
        WeakHashMap<View, ViewPadding>()
    )
    private data class LogoHostState(
        val resourceId: Int? = null,
        val drawable: Drawable? = null
    )

    private val replacedLogoStates = Collections.synchronizedMap(
        WeakHashMap<ImageView, LogoHostState>()
    )
    private val restoringLogoDrawable = ThreadLocal.withInitial { false }
    private val candidateItemRootBaseLeftPaddingPx = Collections.synchronizedMap(
        WeakHashMap<Any, Int>()
    )
    private val candidateItemRootAppliedLeftPaddingPx = Collections.synchronizedMap(
        WeakHashMap<Any, Int>()
    )
    private val candidateMarginInsetWriteDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    private val appearanceColorParamsLock = Any()
    private val hsvScratchThreadLocal = object : ThreadLocal<FloatArray>() {
        override fun initialValue(): FloatArray = FloatArray(3)
    }

    private data class AppearanceColorCache(
        val colors: Map<String, Int>,
        val params: Map<String, AppearanceColorParams>
    )

    @Volatile
    private var appearanceColorCache: AppearanceColorCache? = null

    private data class AppearanceColorParams(
        val mode: WeTypeAppearanceColorMode,
        val userColor: Int,
        val targetHue: Float = 0f,
        val deltaSaturation: Float = 0f,
        val deltaValue: Float = 0f
    )

    fun hookFont(
        fontAsset: String,
        getModuleAssetManager: () -> AssetManager
    ) {
        runCatching {
            Typeface::class.java.getDeclaredMethod(
                "createFromAsset",
                AssetManager::class.java,
                String::class.java
            ).hookBefore { param ->
                if (param[1] != fontAsset) return@hookBefore
                val hostAssets = param[0] as? AssetManager ?: return@hookBefore
                runCatching {
                    // 用户自选字体优先于内置 SF 替换；两者失败时保持宿主原字体。
                    WeTypeSettings.getCustomFontPathXposed()
                        .takeIf { it.isNotBlank() }
                        ?.let(::loadCustomTypeface)
                        ?: SfSymbols.getTypeface(getModuleAssetManager(), hostAssets = hostAssets)
                }.getOrNull()?.let { param.result = it }
            }
            Log.i("Success: Hook SF Pro with host WeType font fallback")
        }.onFailure {
            Log.i("Failed: Hook WeType font replacement")
            Log.i(it)
        }
    }

    /** 用户自选字体按路径+文件指纹缓存；Typeface.createFromFile 每次都要 mmap,避免字体解析热路径反复读盘。 */
    private fun loadCustomTypeface(path: String): Typeface? {
        val file = java.io.File(path)
        if (!file.isFile) return null
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        customFontCache?.let { (cachedKey, face) -> if (cachedKey == cacheKey) return face }
        val face = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        customFontCache = cacheKey to face
        return face
    }

    /**
     * 键盘绘制器的 Paint 字体补写。绘制器(drawmethod e/b/f/g/h/i 等)构造时通过
     * createFromAsset("fonts/WE-Regular.ttf") 初始化 Paint 的 typeface,而构造发生在
     * 进程启动极早期、字体 hook 就绪之前(createFromAsset hook 装载后从未触发,PSET-INIT
     * 证实构造期已过)——之后重绘复用已构造的 Paint,不再走 setTypeface。改为挂各类
     * 构造,完成后把本类所有 Paint 字段补写为用户字体(缺省回退内置 SF 替换)。
     */
    fun hookKeyboardPaintTypeface(getModuleAssetManager: () -> AssetManager) {
        runCatching {
            android.graphics.Paint::class.java.getMethod(
                "setTypeface", Typeface::class.java
            ).hookAfter { param ->
                val paint = param.thisObject as? android.graphics.Paint ?: return@hookAfter
                val stack = Throwable().stackTrace
                val fromKeyboard = stack.any {
                    it.className.contains("keyboard.selfdraw.drawmethod")
                }
                if (!fromKeyboard) return@hookAfter
                val face = WeTypeSettings.getCustomFontPathXposed()
                    .takeIf { it.isNotBlank() }
                    ?.let(::loadCustomTypeface)
                    ?: runCatching {
                        SfSymbols.getTypeface(getModuleAssetManager(), hostAssets = null)
                    }.getOrNull()
                    ?: return@hookAfter
                if (param[0] !== face) paint.typeface = face
            }
            Log.i("Success: Hook keyboard paint typeface")
        }.onFailure {
            Log.i("Failed: Hook keyboard paint typeface")
            Log.i(it)
        }
        // 候选栏自绘 item(selfview.s)的 TextPaint 构造后直接复用,不走 drawmethod——
        // 在其绘制方法 R(Context,Canvas,Rect) 里补写字体。
        deferredPluginInstall(
            "wetype.candidate-typeface",
            "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview.s"
        ) {
            runCatching {
                val cls = loadClassOrNull(
                    "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview.s"
                ) ?: return@deferredPluginInstall
                val paintFields = cls.declaredFields.filter {
                    it.type == android.graphics.Paint::class.java ||
                        it.type == android.text.TextPaint::class.java
                }
                if (paintFields.isEmpty()) return@deferredPluginInstall
                paintFields.forEach { it.isAccessible = true }
                val drawMethod = cls.getDeclaredMethod(
                    "R",
                    android.content.Context::class.java,
                    Canvas::class.java,
                    android.graphics.Rect::class.java
                )
                drawMethod.hookAfter { param ->
                    val face = WeTypeSettings.getCustomFontPathXposed()
                        .takeIf { it.isNotBlank() }
                        ?.let(::loadCustomTypeface)
                        ?: return@hookAfter
                    paintFields.forEach { field ->
                        runCatching {
                            (field.get(param.thisObject) as? android.graphics.Paint)
                                ?.takeIf { it.typeface !== face }
                                ?.setTypeface(face)
                        }
                    }
                }
                Log.i("Success: Hook candidate paint typeface")
            }.onFailure {
                Log.i("Failed: Hook candidate paint typeface")
                Log.i(it)
            }
        }
    }

    @Volatile
    private var customFontCache: Pair<String, Typeface>? = null


    private fun <T> resolveResourceIds(
        nameToValueMap: Map<String, T>,
        vararg classNames: String
    ): Map<Int, T> {
        val result = mutableMapOf<Int, T>()
        val unresolvedNames = nameToValueMap.keys.toMutableSet()
        for (className in classNames) {
            if (unresolvedNames.isEmpty()) break
            val clazz = loadClassOrNull(className) ?: continue
            val iterator = unresolvedNames.iterator()
            while (iterator.hasNext()) {
                val name = iterator.next()
                val field = runCatching { clazz.getField(name) }.getOrNull()
                if (field != null) {
                    val id = runCatching { field.getInt(null) }.getOrNull()
                    if (id != null) {
                        result[id] = nameToValueMap[name]!!
                        iterator.remove()
                    }
                }
            }
        }
        if (unresolvedNames.isNotEmpty()) {
            Log.i("Failed to resolve resource IDs for: $unresolvedNames")
        }
        return result
    }

    /**
     * Resource IDs resolved on the first hook callback instead of at install time: the plugin
     * R-classes live in the runtime-decrypted plugin dex, which does not exist yet when hooks
     * install inside Application.attach (same root cause as the f2 backdrop fix).
     */
    private fun <T> lazyResourceIds(
        nameToValueMap: Map<String, T>,
        vararg classNames: String
    ): Lazy<Map<Int, T>> = lazy {
        resolveResourceIds(nameToValueMap, *classNames).also { resolved ->
            if (resolved.isEmpty()) Log.i("Resource IDs still unresolved at first callback")
        }
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * For hooks whose TARGET method lives on a plugin class: the class does not exist yet at
     * install time, so the install itself must wait. Retries on the main looper until the class
     * resolves (immediately when already loaded, e.g. hot reload), then runs the install once.
     */
    private fun deferredPluginInstall(scope: String, className: String, install: () -> Unit) {
        fun attempt(attempt: Int) {
            val ready = runCatching { loadClassOrNull(className) != null }.getOrDefault(false)
            when {
                ready -> runCatching { install() }.onFailure {
                    Log.i("Failed: deferred install $scope")
                    Log.i(it)
                }
                attempt < 40 -> mainHandler.postDelayed({ attempt(attempt + 1) }, 250L)
                else -> Log.i("Gave up: deferred install $scope ($className never loaded)")
            }
        }
        attempt(0)
    }

    fun hookXmlDrawables(
        drawableReplacements: Map<String, Int>,
        getModuleResources: (Resources) -> Resources
    ) {
        runCatching {
            val resolvedReplacements by lazyResourceIds(drawableReplacements, "com.tencent.wetype.plugin.hld.r", "com.tencent.wetype.plugin.hld.s")
            Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param[0] as? Int ?: return@hookAfter
                    if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                    replaceDrawable(resources, resId, null, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Resources::class.java.getMethod(
                "getDrawable",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val resId = param[0] as? Int ?: return@hookAfter
                if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                val theme = param[1] as? Resources.Theme
                replaceDrawable(resources, resId, theme, resolvedReplacements, getModuleResources)
                    ?.also { param.result = it }
            }
            runCatching {
                Resources::class.java.getMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param[0] as? Int ?: return@hookAfter
                    if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                    replaceDrawable(resources, resId, null, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            }
            runCatching {
                Resources::class.java.getMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java
                ).hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param[0] as? Int ?: return@hookAfter
                    if (!shouldReplaceDrawable(resources, resId, resolvedReplacements)) return@hookAfter
                    val theme = param[2] as? Resources.Theme
                    replaceDrawable(resources, resId, theme, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            }
            TypedArray::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param[0] as? Int ?: return@hookAfter
                    val resId = typedArray.getResourceId(index, 0)
                    if (resId == 0) return@hookAfter
                    if (!shouldReplaceDrawable(typedArray.resources, resId, resolvedReplacements)) return@hookAfter
                    replaceDrawable(typedArray.resources, resId, null, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Log.i("Success: Hook WeType xml drawables")
        }.onFailure {
            Log.i("Failed: Hook WeType xml drawables")
            Log.i(it)
        }
    }

    fun hookAppearanceColors(staticColorReplacements: Map<String, Int>) {
        runCatching {
            val resolvedStatic = lazyResourceIds(staticColorReplacements, "com.tencent.wetype.plugin.hld.p", "com.tencent.wetype.plugin.hld.s")
            val dynamicMap = mutableMapOf<String, WeTypeAppearanceColorGroup>()
            WeTypeAppearanceColorGroups.groups.forEach { group ->
                group.colorResourceNames.forEach { name -> dynamicMap[name] = group }
            }
            val resolvedDynamic = lazyResourceIds(dynamicMap, "com.tencent.wetype.plugin.hld.p", "com.tencent.wetype.plugin.hld.s")

            val themeAttrMap = mutableMapOf<String, WeTypeAppearanceColorGroup>()
            WeTypeAppearanceColorGroups.groups.forEach { group ->
                group.themeAttributeNames.forEach { name -> themeAttrMap[name] = group }
            }
            val resolvedThemeAttrs = lazyResourceIds(themeAttrMap, "com.tencent.wetype.plugin.hld.o", "com.tencent.wetype.plugin.hld.s")

            hookThemeStyledAttributes(resolvedThemeAttrs)
            hookColorValueAccess(resolvedStatic, resolvedDynamic, resolvedThemeAttrs)
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val colorResId = param[0] as? Int ?: return@hookAfter
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    if (!shouldReplaceColor(resources, colorResId, resolvedStatic.value, resolvedDynamic.value)) return@hookAfter
                    param.result = replaceColor(colorResId, param.result as Int, resolvedStatic.value, resolvedDynamic.value)
                }
            Resources::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val colorResId = param[0] as? Int ?: return@hookAfter
                val resources = param.thisObject as? Resources ?: return@hookAfter
                if (!shouldReplaceColor(resources, colorResId, resolvedStatic.value, resolvedDynamic.value)) return@hookAfter
                param.result = replaceColor(colorResId, param.result as Int, resolvedStatic.value, resolvedDynamic.value)
            }
            Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val colorResId = param[0] as? Int ?: return@hookAfter
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    if (!shouldReplaceColor(resources, colorResId, resolvedStatic.value, resolvedDynamic.value)) return@hookAfter
                    param.result = replaceColorStateList(
                        colorResId,
                        param.result as ColorStateList,
                        resolvedStatic.value,
                        resolvedDynamic.value
                    )
                }
            Resources::class.java.getMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val colorResId = param[0] as? Int ?: return@hookAfter
                val resources = param.thisObject as? Resources ?: return@hookAfter
                if (!shouldReplaceColor(resources, colorResId, resolvedStatic.value, resolvedDynamic.value)) return@hookAfter
                param.result = replaceColorStateList(
                    colorResId,
                    param.result as ColorStateList,
                    resolvedStatic.value,
                    resolvedDynamic.value
                )
            }
            TypedArray::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).hookAfter { param ->
                val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                val index = param[0] as? Int ?: return@hookAfter
                val colorResId = typedArray.getResourceId(index, 0)
                if (colorResId != 0) {
                    if (!shouldReplaceColor(typedArray.resources, colorResId, resolvedStatic.value, resolvedDynamic.value)) return@hookAfter
                    param.result = replaceColor(
                        colorResId,
                        param.result as Int,
                        resolvedStatic.value,
                        resolvedDynamic.value
                    )
                    return@hookAfter
                }
                replaceThemeAttributeColor(
                    typedArray,
                    index,
                    param.result as Int,
                    resolvedThemeAttrs.value
                )?.also { param.result = it }
            }
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param[0] as? Int ?: return@hookAfter
                    val colorResId = typedArray.getResourceId(index, 0)
                    val colorStateList = param.result as? ColorStateList ?: return@hookAfter
                    if (colorResId != 0) {
                        if (!shouldReplaceColor(typedArray.resources, colorResId, resolvedStatic.value, resolvedDynamic.value)) return@hookAfter
                        param.result = replaceColorStateList(
                            colorResId,
                            colorStateList,
                            resolvedStatic.value,
                            resolvedDynamic.value
                        )
                        return@hookAfter
                    }
                    replaceThemeAttributeColor(
                        typedArray,
                        index,
                        colorStateList.defaultColor,
                        resolvedThemeAttrs.value
                    )
                        ?.also { param.result = ColorStateList.valueOf(it) }
                }
            Log.i("Success: Hook WeType appearance colors")
        }.onFailure {
            Log.i("Failed: Hook WeType appearance colors")
            Log.i(it)
        }
    }

    private fun hookColorValueAccess(
        resolvedStatic: Lazy<Map<Int, Int>>,
        resolvedDynamic: Lazy<Map<Int, WeTypeAppearanceColorGroup>>,
        resolvedThemeAttrs: Lazy<Map<Int, WeTypeAppearanceColorGroup>>
    ) {
        Resources::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java,
            Boolean::class.javaPrimitiveType
        ).hookAfter { param ->
            val resources = param.thisObject as? Resources ?: return@hookAfter
            val outValue = param[1] as? TypedValue ?: return@hookAfter
            val static = resolvedStatic.value
            val dynamic = resolvedDynamic.value
            if (!shouldReplaceTypedValue(resources, outValue, static, dynamic)) return@hookAfter
            replaceTypedValue(outValue, static, dynamic)
        }
        runCatching {
            Resources::class.java.getMethod(
                "getValueForDensity",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val outValue = param[2] as? TypedValue ?: return@hookAfter
                val static = resolvedStatic.value
                val dynamic = resolvedDynamic.value
                if (!shouldReplaceTypedValue(resources, outValue, static, dynamic)) return@hookAfter
                replaceTypedValue(outValue, static, dynamic)
            }
        }
        TypedArray::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java
        ).hookAfter { param ->
            if (param.result != true) return@hookAfter
            val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
            val outValue = param[1] as? TypedValue ?: return@hookAfter
            val static = resolvedStatic.value
            val dynamic = resolvedDynamic.value
            if (!shouldReplaceTypedValue(typedArray.resources, outValue, static, dynamic)) return@hookAfter
            replaceTypedValue(outValue, static, dynamic)
        }
        TypedArray::class.java.getMethod("peekValue", Int::class.javaPrimitiveType)
            .hookAfter { param ->
                val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                val outValue = param.result as? TypedValue ?: return@hookAfter
                val static = resolvedStatic.value
                val dynamic = resolvedDynamic.value
                if (!shouldReplaceTypedValue(typedArray.resources, outValue, static, dynamic)) return@hookAfter
                replaceTypedValue(outValue, static, dynamic)
            }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "resolveAttribute",
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                if (param.result != true) return@hookAfter
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val resources = theme.resources
                val outValue = param[1] as? TypedValue ?: return@hookAfter
                val attrResId = param[0] as Int
                val static = resolvedStatic.value
                val dynamic = resolvedDynamic.value
                val themeAttrs = resolvedThemeAttrs.value
                if (
                    !isTargetWeTypeTypedValue(resources, outValue, static, dynamic) &&
                    !isTargetWeTypeThemeAttr(resources, attrResId, themeAttrs)
                ) return@hookAfter
                if (replaceTypedValue(outValue, static, dynamic)) {
                    return@hookAfter
                }
                replaceThemeAttributeTypedValue(attrResId, outValue, themeAttrs)
            }
        }
    }

    /**
     * Recolors the self-draw keyboard keys by intercepting the host's theme-attribute color
     * resolution (`Resources.Theme.resolveAttribute`), the single funnel every self-draw key
     * fill/shadow/border color is resolved through — both the layout-JSON `@attr/?attr` button
     * colors (the bulk of keys) and the few programmatically-styled keys.
     *
     * Scoping is done purely by the stable R.attr field names of the key background attributes
     * (resolved via the positionally-stable R.attr class, never by version-specific method/entry
     * names). The "white" attributes cover normal/QWERTY keys and the "grey" attributes cover
     * special/function keys (backspace, "123", symbols, caps lock, ...). Accent keys (the green
     * "搜索"/enter button, `ime_color_btn_green_*`) and every non-key surface are intentionally left
     * untouched. The light/dark variants share the same attribute id and are disambiguated at
     * resolve time from the host's active skin, matching the background profile even when the
     * keyboard uses a different mode from Android. Matching on the attribute id is
     * what makes this robust: the skin frequently resolves these attributes to inline colors whose
     * `TypedValue.resourceId` is 0.
     */
    fun hookSelfDrawKeyColors() {
        runCatching {
            val keyAttrRoles by lazyResourceIds(
                mapOf(
                    // Skin-layout button backgrounds (the bulk of keys: QWERTY/normal -> "white",
                    // special keys such as backspace/"123"/symbols -> "grey"). These are what the
                    // keyboard JSON references via @attr/?attr and are resolved through
                    // Resources.Theme.resolveAttribute.
                    "ime_color_btn_white_bg" to KeyAttrRole.Fill,
                    "ime_color_btn_grey_bg" to KeyAttrRole.Fill,
                    "ime_color_btn_white_shadow" to KeyAttrRole.Shadow,
                    "ime_color_btn_grey_shadow" to KeyAttrRole.Shadow,
                    // Dark-mode key shadow: the layout color arrays use this shared black-50%
                    // attribute as their dark slot for both white and grey keys. It is referenced
                    // exclusively by key shadowColor entries (verified: no code/other-resource use),
                    // so neutralizing the attribute resolution is safe and key-scoped.
                    "UN_BW_0_Alpha_0_5" to KeyAttrRole.Shadow,
                    "ime_color_btn_white_border" to KeyAttrRole.Border,
                    "ime_color_btn_grey_border" to KeyAttrRole.Border,
                    // Key label / icon colors: grey_text covers special keys (backspace, enter,
                    // "123", "#+=", CN/EN toggle) and white_text the normal keys' edge hints.
                    // These resolve through the same @attr funnel as the backgrounds, so mapping
                    // them to the key-text color group (passthrough semantics included) is what
                    // actually recolors function keys — KeyData.mainTextColor only covers letters.
                    "ime_color_btn_white_text" to KeyAttrRole.Text,
                    "ime_color_btn_grey_text" to KeyAttrRole.Text,
                    "ime_color_btn_grey_text_pressed" to KeyAttrRole.Text,
                    // R$attr(o 类)的 ime_key_* 文字家族:程序化路径的功能键(退格/回车/123/#+=/中英)
                    // 文字与图标、次级文字(侧边栏符号)。注意这些是 attr 不是 color 资源,
                    // 放进 colorResourceNames 的组会在 ID 解析阶段失败(日志已验证)。
                    "ime_key_text_color" to KeyAttrRole.Text,
                    "ime_key_text_secondary_color" to KeyAttrRole.Text,
                    "ime_key_white_text_color" to KeyAttrRole.Text,
                    "ime_key_white_text_color_pressed" to KeyAttrRole.Text,
                    "ime_key_grey_text_color" to KeyAttrRole.Text,
                    "ime_key_grey_text_color_pressed" to KeyAttrRole.Text,
                    "ime_key_back_icon_pressed_color" to KeyAttrRole.Text,
                    // 候选栏文字:candidate.D.v 绑定候选项时用 ime_key_text_color(普通候选)与
                    // ime_color_06(首个高亮候选)设置 selfview.s 的文字资源 id(逆向实锤,
                    // G0 调用点)。普通候选跟随文字色组,首个候选走候选词强调色组。
                    "ime_color_06" to KeyAttrRole.Candidate,
                    // candidate.W.n 的联想词候选(fillContent)文字色。
                    "ime_color_05" to KeyAttrRole.Candidate,
                    // 空格键语音图标(o$u0.a)的 tint:j.Q0(q1.m(ime_color_00)) 逆向实锤。
                    // ime_color_00 是全局黑槽位(候选栏小字/剪贴板等共用),挂文字色做统一染色。
                    "ime_color_00" to KeyAttrRole.Text,
                    // Programmatically-styled keys (e.g. caps lock / enter / separator) that read the
                    // key colors directly instead of from the layout JSON.
                    "ime_key_white_color" to KeyAttrRole.Fill,
                    "ime_key_grey_color" to KeyAttrRole.Fill,
                    "ime_key_white_shadow_color" to KeyAttrRole.Shadow,
                    "ime_key_grey_shadow_color" to KeyAttrRole.Shadow,
                    "ime_key_white_border_color" to KeyAttrRole.Border,
                    "ime_key_grey_border_color" to KeyAttrRole.Border
                ),
                WETYPE_ATTR_R_CLASS,
                WETYPE_ID_R_CLASS
            )

            Resources.Theme::class.java.getMethod(
                "resolveAttribute",
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                if (param.result != true) return@hookAfter
                val attrResId = param[0] as? Int ?: return@hookAfter
                val role = keyAttrRoles[attrResId] ?: return@hookAfter
                val outValue = param[1] as? TypedValue ?: return@hookAfter
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val replacement = when (role) {
                    KeyAttrRole.Fill -> resolveKeyColor(theme)
                    KeyAttrRole.Text -> resolveKeyTextColorOrNull(theme.resources) ?: return@hookAfter
                    KeyAttrRole.Candidate -> WeTypeSettings.getAppearanceColorXposed(
                        CANDIDATE_COLOR_GROUP_ID
                    )
                    KeyAttrRole.Shadow -> Color.TRANSPARENT
                    // Keep a faint hairline using the host's own border hue at low opacity.
                    KeyAttrRole.Border -> withForcedAlpha(outValue.data, KEY_BORDER_ALPHA)
                }
                if (outValue.resourceId != 0 && (role == KeyAttrRole.Text || role == KeyAttrRole.Candidate)) {
                    // 两步取色:attr 先解析出颜色资源 id,宿主再单独 getColor 该 id。
                    // 登记 id → 对应颜色组,getColor 家族查表命中后同样替换。
                    val overrideGroup = if (role == KeyAttrRole.Candidate) {
                        CANDIDATE_COLOR_GROUP_ID
                    } else {
                        KEY_TEXT_COLOR_GROUP_ID
                    }
                    runtimeColorOverrides[outValue.resourceId] = WeTypeAppearanceColorGroups.findById(
                        overrideGroup
                    ) ?: return@hookAfter
                }
                applyColorToTypedValue(outValue, replacement)
            }

            Log.i("Success: Hook WeType self-draw key colors")
        }.onFailure {
            Log.i("Failed: Hook WeType self-draw key colors")
            Log.i(it)
        }
    }

    private enum class KeyAttrRole { Fill, Text, Candidate, Shadow, Border }

    /**
     * Overrides the per-key background corner radius. The host reads [KeyData.bgCorner] (a design-unit
     * value, default 16) for every key and scales it together with the key size, so returning the
     * user's configured value here applies a uniform, size-proportional corner to all keys. Hooked on
     * the readable, serialization-stable bean class/getter rather than any obfuscated identifier.
     */
    fun hookKeyboardKeyCorner() {
        deferredPluginInstall("wetype.key-corner", KEY_DATA_CLASS) {
            runCatching {
                val keyDataClass = loadClassOrNull(KEY_DATA_CLASS)
                    ?: error("Failed to load KeyData")
                keyDataClass.getMethod("getBgCorner").hookAfter { param ->
                    // KeyData.getBgCorner() returns a nullable Float; force it to the configured value so
                    // every key (and the host's null-default fallback) uses the same corner radius. The
                    // host then scales this design-unit radius together with the key dimensions.
                    val corner = WeTypeSettings.getKeyCornerRadiusXposed()
                    param.result = WeTypeSettings.keyCornerRadiusToHostUnits(corner)
                }
                Log.i("Success: Hook WeType keyboard key corner")
            }.onFailure {
                Log.i("Failed: Hook WeType keyboard key corner")
                Log.i(it)
            }
        }
    }

    /**
     * 按键文字与按键图标颜色。4.0.1 的自绘按键从 KeyData bean 的字符串字段取色
     * (mainTextColor/mainTextColorPressed/iconColor/iconPressedColor,getter 返回
     * "#AARRGGBB" 形态的 String),不走 Resources 的颜色资源解析,所以资源替换路径
     * 对按键本体无效(品牌强调色染不到文字/图标的根因)。与 getBgCorner 同款 bean hook:
     * 文字色带透传语义,未自定义时不动返回值;图标色与工具栏/logo 同组,始终应用。
     */
    fun hookKeyboardKeyTextColor() {
        deferredPluginInstall("wetype.key-text-color", KEY_DATA_CLASS) {
            runCatching {
                val keyDataClass = loadClassOrNull(KEY_DATA_CLASS)
                    ?: error("Failed to load KeyData")
                fun hookColorGetter(getter: String, color: () -> Int?) {
                    keyDataClass.getMethod(getter).hookAfter { param ->
                        color()?.let { param.result = String.format(java.util.Locale.US, "#%08X", it) }
                    }
                }
                hookColorGetter("getMainTextColor") { resolveKeyTextColorXposed() }
                hookColorGetter("getMainTextColorPressed") { resolveKeyTextColorXposed() }
                // 侧边栏长按提示符号字段(追踪确认其 orig=@attr/ime_color_07 引用,bean 路径有效)。
                hookColorGetter("getFloatTextColor") { resolveKeyTextColorXposed() }
                // 退格/大写锁定/123 等图标键原版跟随品牌强调色;用户自定义文字色后统一跟随
                // 文字色,未自定义(passThrough)时回退图标色组保持旧行为。
                hookColorGetter("getIconColor") { resolveKeyIconColorPreferText() }
                hookColorGetter("getIconPressedColor") { resolveKeyIconColorPreferText() }
                // getInitialColor 已证实是灰色键背景字段(orig=@attr/ime_color_btn_grey_bg),
                // 挂文字色会污染按键背景,不再挂钩。
                Log.i("Success: Hook WeType keyboard key text/icon colors")
            }.onFailure {
                Log.i("Failed: Hook WeType keyboard key text color")
                Log.i(it)
            }
        }
    }

    /** null = 用户未自定义，透传宿主原色。 */
    private fun resolveKeyTextColorOrNull(resources: Resources?): Int? {
        val color = WeTypeSettings.getAppearanceColorXposed(KEY_TEXT_COLOR_GROUP_ID, resources)
        val group = WeTypeAppearanceColorGroups.findById(KEY_TEXT_COLOR_GROUP_ID) ?: return null
        if (group.passThroughByDefault && color == group.defaultColor) return null
        return color
    }

    /**
     * 中英切换键的"中"/"英"标签：它们不是文字而是皮肤按强调色预渲染的位图字形
     * (keyboard_switch_left_zhong_nor/sel、keyboard_switch_right_english_nor/sel,
     * R 原名逆向实锤；全通道采样证实渲染不走 Paint/setTextColor/tint)。位图是
     * 透明底单色字形，SRC_ATOP 可整体染色——资源名稳定按名解析,随文字色组染色;
     * 用户未自定义文字色时透传宿主位图。
     */
    fun hookKeyboardSwitchLabelTint() {
        val labelIds by lazyResourceIds(
            mapOf(
                "keyboard_switch_left_zhong_nor" to Unit,
                "keyboard_switch_left_zhong_sel" to Unit,
                "keyboard_switch_right_english_nor" to Unit,
                "keyboard_switch_right_english_sel" to Unit
            ),
            WETYPE_DRAWABLE_R_CLASS
        )
        runCatching {
            // 标签位图的 Bitmap 对象身份登记:getDrawable 命中时记录,渲染终点按此识别。
            // ConstantState 派生的所有 drawable 实例共享同一 Bitmap,身份匹配覆盖全部
            // 派生/缓存实例——单实例染色被缓存绕过的问题由此解决。
            val labelBitmaps = java.util.Collections.newSetFromMap(
                java.util.WeakHashMap<android.graphics.Bitmap, Boolean>()
            )
            java.util.Collections.synchronizedSet(labelBitmaps)
            listOf(
                arrayOf(Int::class.javaPrimitiveType),
                arrayOf(Int::class.javaPrimitiveType, Resources.Theme::class.java)
            ).forEach { signature ->
                Resources::class.java.getMethod("getDrawable", *signature).hookAfter { param ->
                    val id = param[0] as? Int ?: return@hookAfter
                    if (id !in labelIds) return@hookAfter
                    val drawable = param.result as? BitmapDrawable ?: return@hookAfter
                    val bitmap = drawable.bitmap ?: return@hookAfter
                    labelBitmaps.add(bitmap)
                    // 渲染终点兜底染色。
                    drawable.colorFilter =
                        PorterDuffColorFilter(
                            resolveKeyTextColorOrNull(null) ?: return@hookAfter,
                            PorterDuff.Mode.SRC_ATOP
                        )
                }
            }
            BitmapDrawable::class.java.getMethod("draw", Canvas::class.java).hookBefore { param ->
                val drawable = param.thisObject as? BitmapDrawable ?: return@hookBefore
                val bitmap = drawable.bitmap ?: return@hookBefore
                if (bitmap !in labelBitmaps) return@hookBefore
                val color = resolveKeyTextColorOrNull(null) ?: return@hookBefore
                drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
            }
            Log.i("Success: Hook keyboard switch label tint")
        }.onFailure {
            Log.i("Failed: Hook keyboard switch label tint")
            Log.i(it)
        }
    }

    /**
     * 字形位图渲染终点染色。中英切换键的"中"/"英"是皮肤引擎按强调色在内存中渲染的
     * 字形位图(透明底单色字形,Resources.getDrawable 命中的那份并非渲染实例——
     * 全通道采样:Paint 含 init/setTextColor/setTint 族零命中,而视觉颜色随强调色)。
     * 在 Canvas.drawBitmap 入口按内容识别:小尺寸+饱和彩色字形位图 → 生成文字色染色
     * 副本替换绘制参数;识别结果按位图缓存,未自定义文字色时透传。
     */
    fun hookGlyphBitmapRecolor() {
        runCatching {
            val recolorCache = java.util.Collections.synchronizedMap(
                java.util.WeakHashMap<android.graphics.Bitmap, android.graphics.Bitmap?>()
            )
            fun isSaturated(color: Int): Boolean {
                if (Color.alpha(color) < 0x30) return false
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                return maxOf(r, g, b) - minOf(r, g, b) >= 40
            }

            fun recolorGlyph(src: android.graphics.Bitmap): android.graphics.Bitmap? {
                val textColor = resolveKeyTextColorOrNull(null) ?: return null
                val w = src.width
                val h = src.height
                val pixels = IntArray(w * h)
                runCatching { src.getPixels(pixels, 0, w, 0, 0, w, h) }.getOrNull() ?: return null
                var opaque = 0
                var saturated = 0
                for (pixel in pixels) {
                    if (Color.alpha(pixel) < 0x30) continue
                    opaque++
                    if (isSaturated(pixel)) saturated++
                }
                // 字形位图特征:非透明像素里饱和色占比过半;全透明/照片类位图不适用。
                if (opaque == 0 || saturated * 100 < opaque * 50) return null
                val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                val paint = android.graphics.Paint()
                paint.colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_ATOP)
                android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
                return out
            }

            fun intercept(bitmap: android.graphics.Bitmap): android.graphics.Bitmap? {
                if (bitmap.width > 240 || bitmap.height > 180) return null
                synchronized(recolorCache) {
                    if (recolorCache.containsKey(bitmap)) return recolorCache[bitmap]
                    val replacement = runCatching { recolorGlyph(bitmap) }.getOrNull()
                    recolorCache[bitmap] = replacement
                    return replacement
                }
            }

            listOf(
                arrayOf(
                    android.graphics.Bitmap::class.java,
                    Float::class.java,
                    Float::class.java,
                    android.graphics.Paint::class.java
                ),
                arrayOf(
                    android.graphics.Bitmap::class.java,
                    android.graphics.Rect::class.java,
                    android.graphics.Rect::class.java,
                    android.graphics.Paint::class.java
                ),
                arrayOf(
                    android.graphics.Bitmap::class.java,
                    android.graphics.Matrix::class.java,
                    android.graphics.Paint::class.java
                )
            ).forEach { signature ->
                runCatching {
                    Canvas::class.java.getMethod("drawBitmap", *signature).hookBefore { param ->
                        val bitmap = param[0] as? android.graphics.Bitmap ?: return@hookBefore
                        val replacement = intercept(bitmap) ?: return@hookBefore
                        param[0] = replacement
                    }
                }
            }
            // 皮肤引擎单色字形位图的填充原语:字形 alpha mask + eraseColor(强调色) /
            // drawColor(强调色)——不走 Paint,绕过全部采样。eraseColor 宽松匹配(小尺寸
            // 位图的任何饱和彩色填充→文字色,皮肤绿的取值未必等于组值);drawColor 精确
            // 匹配强调色(大底误伤风险高)。
            val accentColor = WeTypeSettings.getAppearanceColorXposed("theme_color")
            val textColor = resolveKeyTextColorOrNull(null)
            if (textColor != null) {
                runCatching {
                    android.graphics.Bitmap::class.java.getMethod(
                        "eraseColor", Int::class.javaPrimitiveType
                    ).hookBefore { param ->
                        val bitmap = param.thisObject as? android.graphics.Bitmap ?: return@hookBefore
                        val color = param[0] as Int
                        if (color == textColor) return@hookBefore
                        val r = Color.red(color)
                        val g = Color.green(color)
                        val b = Color.blue(color)
                        if (maxOf(r, g, b) - minOf(r, g, b) < 40) return@hookBefore
                        if (bitmap.width > 240 || bitmap.height > 180) return@hookBefore
                        param[0] = textColor
                    }
                    Canvas::class.java.getMethod(
                        "drawColor", Int::class.javaPrimitiveType
                    ).hookBefore { param ->
                        if (accentColor != 0 && param[0] == accentColor) {
                            param[0] = textColor
                        }
                    }
                    Log.i("Success: Hook glyph mask erase/draw color swap")
                }
            }
            Log.i("Success: Hook glyph bitmap recolor")
        }.onFailure {
            Log.i("Failed: Hook glyph bitmap recolor")
            Log.i(it)
        }
    }

    /**
     * 中英切换键的"中"/"英"标签 TextView 精准染色。两个标签的色资源不同源："中"走
     * ime_key_text_color(被文字色组覆盖,已生效)，"英"绑定的却是与绿色强调键背景共享的
     * 品牌色槽(全通道采样实锤:Paint/setTextColor/tint 均无其独立设色，色值随强调色
     * 颜色与透明度同步)——资源层替换会误伤气泡与强调键背景，故按标签文字内容在
     * TextView 层直接改色。
     */
    fun hookCnEnLabelColor() {
        runCatching {
            android.widget.TextView::class.java.getMethod(
                "setText", CharSequence::class.java
            ).hookAfter { param ->
                val view = param.thisObject as? android.widget.TextView ?: return@hookAfter
                if (!view.javaClass.name.startsWith("com.tencent.wetype")) return@hookAfter
                val text = param[0]?.toString() ?: return@hookAfter
                if (text != "中" && text != "英") return@hookAfter
                val color = WeTypeSettings.getAppearanceColorXposed(KEY_TEXT_COLOR_GROUP_ID)
                view.setTextColor(color)
            }
            Log.i("Success: Hook CN/EN switch label color")
        }.onFailure {
            Log.i("Failed: Hook CN/EN switch label color")
            Log.i(it)
        }
    }

    private fun resolveKeyTextColorXposed(): Int? = resolveKeyTextColorOrNull(null)

    private fun keyIconColorXposed(): Int =
        WeTypeSettings.getAppearanceColorXposed(ICON_COLOR_GROUP_ID)

    /** 按键图标色:用户自定义过文字色时优先跟随文字色,否则回退图标色组。 */
    private fun resolveKeyIconColorPreferText(): Int =
        resolveKeyTextColorOrNull(null) ?: keyIconColorXposed()

    private fun resolveKeyColor(theme: Resources.Theme): Int {
        val isDarkMode = WeTypeSettings.isHostDarkMode(theme.resources)
        val groupId = if (isDarkMode) DARK_KEY_COLOR_GROUP_ID else LIGHT_KEY_COLOR_GROUP_ID
        return WeTypeSettings.getAppearanceColorXposed(groupId, theme.resources)
    }

    private fun applyColorToTypedValue(outValue: TypedValue, color: Int) {
        outValue.type = if (Color.alpha(color) == 0xFF) {
            TypedValue.TYPE_INT_COLOR_RGB8
        } else {
            TypedValue.TYPE_INT_COLOR_ARGB8
        }
        outValue.data = color
        outValue.assetCookie = 0
        outValue.resourceId = 0
        outValue.string = null
    }

    fun hookCandidateSpecialTextColor() {
        deferredPluginInstall("wetype.candidate-text", CANDIDATE_VIEW_CLASS) {
            runCatching {
                resolveCandidateSpecialTextColorMethod().hookAfter { param ->
                    param.result = WeTypeSettings.getAppearanceColorXposed(CANDIDATE_COLOR_GROUP_ID)
                }
                Log.i("Success: Hook candidate special text color")
            }.onFailure {
                Log.i("Failed: Hook candidate special text color")
                Log.i(it)
            }
        }
    }

    fun hookCandidateBackgroundCorner() {
        deferredPluginInstall("wetype.candidate-corner", CANDIDATE_SCROLL_VIEW_CLASS) {
            runCatching {
                val candidateViewClass = resolveCandidateSelfViewBaseClass()
            val cornerSetter = resolveKotlinPropertySetter(
                owner = candidateViewClass,
                propertyName = "cornerSize"
            )
            if (cornerSetter != null) {
                cornerSetter.hookBefore { param ->
                    param[0] = WeTypeSettings.getCandidateBackgroundCornerXposed().roundToInt()
                }
            } else {
                // WeType 3.4 and earlier stripped Kotlin metadata. Keep the old field as a narrow
                // compatibility fallback, while locating the draw call by its stable signature.
                val legacyCornerField = candidateViewClass.getDeclaredField("g").also {
                    it.isAccessible = true
                }
                resolveCandidateBackgroundDrawMethod(candidateViewClass).hookBefore { param ->
                    legacyCornerField.setInt(
                        param.thisObject,
                        WeTypeSettings.getCandidateBackgroundCornerXposed().roundToInt()
                    )
                }
            }
                Log.i("Success: Hook candidate background corner")
            }.onFailure {
                Log.i("Failed: Hook candidate background corner")
                Log.i(it)
            }
        }
    }

    fun hookCandidateBackgroundAlpha() {
        deferredPluginInstall("wetype.candidate-alpha", CANDIDATE_SCROLL_VIEW_CLASS) {
            runCatching {
                val candidateViewClass = resolveCandidateSelfViewBaseClass()
            val colorMethods = candidateViewClass.declaredMethods.filter { method ->
                Modifier.isPrivate(method.modifiers) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            check(colorMethods.isNotEmpty()) {
                "Failed to find candidate background color resolver"
            }
            colorMethods.forEach { method ->
                method.hookAfter { param ->
                    val color = param.result as? Int ?: return@hookAfter
                    if (Color.alpha(color) == 0) return@hookAfter
                    param.result = withForcedAlpha(
                        color,
                        WeTypeSettings.getCandidateBackgroundAlphaXposed()
                    )
                }
            }
                Log.i("Success: Hook candidate background alpha")
            }.onFailure {
                Log.i("Failed: Hook candidate background alpha")
                Log.i(it)
            }
        }
    }

    fun hookCandidateBackgroundLeftMargin() {
        deferredPluginInstall("wetype.candidate-margin", CANDIDATE_SCROLL_VIEW_CLASS) {
            runCatching {
                val scrollViewClass = loadClassOrNull(CANDIDATE_SCROLL_VIEW_CLASS)
                    ?: error("Failed to load candidate scroll view")
            val candidateItemRootClass = resolveCandidateItemRootClass(scrollViewClass)
            val setInsetMethod = candidateItemRootClass.findMethodInHierarchy {
                parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaObjectType,
                        Int::class.javaObjectType,
                        Int::class.javaObjectType,
                        Int::class.javaObjectType
                    )
                ) && returnType == Void.TYPE
            }
            val positionMethod = candidateItemRootClass.findMethod {
                parameterTypes.isEmpty() && returnType == Int::class.javaPrimitiveType
            }
            val contextMethod = candidateItemRootClass.findMethodInHierarchy {
                parameterTypes.isEmpty() && returnType == Context::class.java
            }
            val holderClass = candidateItemRootClass.declaredFields
                .map { it.type }
                .firstOrNull { it.enclosingClass == scrollViewClass }
                ?: error("Failed to resolve candidate holder class")
            val holderItemRootMethod = holderClass.findMethod {
                parameterTypes.isEmpty() && candidateItemRootClass.isAssignableFrom(returnType)
            }
            val bindMethod = scrollViewClass.declaredClasses
                .asSequence()
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { method ->
                    !Modifier.isAbstract(method.modifiers) &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(
                            arrayOf(holderClass, Int::class.javaPrimitiveType)
                        )
                }
                ?.also { it.isAccessible = true }
                ?: error("Failed to resolve candidate holder bind method")

            setInsetMethod.hookBefore { param ->
                if (candidateMarginInsetWriteDepth.get() != 0) return@hookBefore
                val itemRoot = param.thisObject ?: return@hookBefore
                if (!candidateItemRootClass.isInstance(itemRoot)) return@hookBefore
                val leftPadding = param[0] as? Int ?: return@hookBefore
                candidateItemRootBaseLeftPaddingPx[itemRoot] = leftPadding
                candidateItemRootAppliedLeftPaddingPx.remove(itemRoot)
            }
            setInsetMethod.hookAfter { param ->
                if (candidateMarginInsetWriteDepth.get() != 0) return@hookAfter
                val itemRoot = param.thisObject ?: return@hookAfter
                if (!candidateItemRootClass.isInstance(itemRoot)) return@hookAfter
                val position = runCatching { positionMethod.invoke(itemRoot) as Int }.getOrNull()
                    ?: return@hookAfter
                applyCandidateBackgroundLeftMargin(
                    itemRoot = itemRoot,
                    position = position,
                    setInsetMethod = setInsetMethod,
                    contextMethod = contextMethod
                )
            }
            bindMethod.hookAfter { param ->
                val holder = param[0] ?: return@hookAfter
                val position = param[1] as? Int ?: return@hookAfter
                val itemRoot = runCatching { holderItemRootMethod.invoke(holder) }.getOrNull()
                    ?: return@hookAfter
                applyCandidateBackgroundLeftMargin(
                    itemRoot = itemRoot,
                    position = position,
                    setInsetMethod = setInsetMethod,
                    contextMethod = contextMethod
                )
            }
                Log.i("Success: Hook candidate background left margin")
            }.onFailure {
                Log.i("Failed: Hook candidate background left margin")
                Log.i(it)
            }
        }
    }

    fun hookCandidatePinyinLeftMargin() {
        deferredPluginInstall("wetype.pinyin-margin", CANDIDATE_VIEW_CLASS) {
            runCatching {
                val candidateViewClass = loadClassOrNull(CANDIDATE_VIEW_CLASS)
                    ?: error("Failed to load ImeCandidateView")
            val containerId = loadClassOrNull(WETYPE_ID_R_CLASS)
                ?.getField(CANDIDATE_PINYIN_CONTAINER_ID_NAME)
                ?.getInt(null)
                ?: error("Failed to resolve $CANDIDATE_PINYIN_CONTAINER_ID_NAME")
            candidateViewClass.getDeclaredMethod("onAttachedToWindow").hookAfter { param ->
                val candidateView = param.thisObject as? View ?: return@hookAfter
                val container = candidateView.findViewById<View>(containerId) ?: return@hookAfter
                applyCandidatePinyinLeftMargin(container)
                ensureCandidatePinyinMarginSync(container)
            }
                Log.i("Success: Hook candidate pinyin left margin")
            }.onFailure {
                Log.i("Failed: Hook candidate pinyin left margin")
                Log.i(it)
            }
        }
    }

    fun hookKeyboardLogo() {
        runCatching {
            val sClass = loadClassOrNull("com.tencent.wetype.plugin.hld.s") ?: return
            val logoIvId = sClass.getField("logo_iv").getInt(null)

            ImageView::class.java.getMethod(
                "setImageResource",
                Int::class.javaPrimitiveType
            ).hookBefore { param ->
                val imageView = param.thisObject as? ImageView ?: return@hookBefore
                if (imageView.id != logoIvId || restoringLogoDrawable.get() == true) return@hookBefore

                val resId = param[0] as? Int ?: return@hookBefore
                synchronized(replacedLogoStates) {
                    replacedLogoStates[imageView] = LogoHostState(resourceId = resId)
                }
                imageView.setTag(LOGO_RESOURCE_TAG_KEY, resId)
                imageView.setImageDrawable(WeTypeIconDrawable())
                param.result = null
            }
            ImageView::class.java.getMethod(
                "setImageDrawable",
                Drawable::class.java
            ).hookBefore { param ->
                val imageView = param.thisObject as? ImageView ?: return@hookBefore
                if (imageView.id != logoIvId || restoringLogoDrawable.get() == true) return@hookBefore

                val drawableArg = param.argumentOrNull(0)
                if (drawableArg is WeTypeIconDrawable) return@hookBefore
                synchronized(replacedLogoStates) {
                    replacedLogoStates[imageView] = LogoHostState(drawable = drawableArg as? Drawable)
                }
                imageView.setTag(LOGO_RESOURCE_TAG_KEY, null)
                param[0] = WeTypeIconDrawable()
            }
            Log.i("Success: Hook WeType keyboard logo")
        }.onFailure {
            Log.i("Failed: Hook WeType keyboard logo")
            Log.i(it)
        }
    }

    fun reconcileCurrentKeyboardLogos(rootViews: Collection<View>) {
        val logoIvId = runCatching {
            loadClassOrNull(WETYPE_ID_R_CLASS)?.getField("logo_iv")?.getInt(null)
        }.getOrNull() ?: return
        val candidatePinyinContainerId = runCatching {
            loadClassOrNull(WETYPE_ID_R_CLASS)
                ?.getField(CANDIDATE_PINYIN_CONTAINER_ID_NAME)
                ?.getInt(null)
        }.getOrNull()
        rootViews.forEach { rootView ->
            forEachView(rootView) { view ->
                if (view.id == candidatePinyinContainerId) {
                    applyCandidatePinyinLeftMargin(view)
                    ensureCandidatePinyinMarginSync(view)
                }
                val imageView = view as? ImageView ?: return@forEachView
                if (imageView.id != logoIvId || imageView.drawable is WeTypeIconDrawable) {
                    return@forEachView
                }
                val resourceId = imageView.getTag(LOGO_RESOURCE_TAG_KEY) as? Int
                synchronized(replacedLogoStates) {
                    replacedLogoStates[imageView] = if (resourceId != null) {
                        LogoHostState(resourceId = resourceId)
                    } else {
                        LogoHostState(drawable = imageView.drawable)
                    }
                }
                imageView.setImageDrawable(WeTypeIconDrawable())
            }
        }
    }

    private inline fun forEachView(rootView: View, action: (View) -> Unit) {
        val pending = java.util.ArrayDeque<View>()
        pending.add(rootView)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            action(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }

    /**
     * 延迟 hook 安装前就已 inflate 的工具栏容器不会再走 setBackground,
     * 窗口可见后按当前配置补写一次 alpha,行为对齐 reconcileCurrentKeyboardLogos。
     * 工具栏容器内的矢量图标染「键盘图标颜色」。注意:不要扩大到键盘窗口全量
     * ImageView——自绘键的图标(退格/上档等)由宿主经 ime_key_back_icon_color 等
     * 资源自染色(且该资源已被按键文字组替换),全量染色会在视图层覆盖宿主取色,
     * 造成「替换生效但视图显示旧色」的打架现象。
     */
    /**
     * 上滑选字抽屉(drawmethod.k/SwitchDrawer)在 <clinit> 经 utils.m1$f.h 缓存
     * ime_color_14(0x7f060245) 作气泡背景色;该颜色资源与绿色强调键共用,按资源替换会误伤。
     * 这里 hook m1$f.h 并按调用方过滤:仅当调用栈来自 selfdraw.drawmethod 包才替换为
     * 「按键弹出面板背景色」(透传语义保留),其余调用方(绿色键等)不受影响。
     */
    fun hookSwitchDrawerPopupColor() {
        // 主通道(逆向实锤):气泡背景是 StateListDrawable——floatview.D$j.a 用
        // ime_skin_color_14(选中态)与 ime_skin_transparent(默认态)构建,文字色走
        // ime_key_white_text_color(由按键文字组覆盖)。因此皮肤取色器 m1$f.h 只有在
        // (a)调用栈来自 floatview 且 (b)解析的资源正是 ime_skin_color_14 时才替换为
        // 弹出色(含独立透明度)——未选中气泡(transparent)与其他 floatview 取色不受影响。
        val popupAccentColorIds by lazyResourceIds(
            mapOf("ime_skin_color_14" to Unit),
            WETYPE_COLOR_R_CLASS
        )
        deferredPluginInstall("wetype.popup-switch-drawer-color", "com.tencent.wetype.plugin.hld.utils.m1\$f") {
            runCatching {
                val helperClass = loadClassOrNull("com.tencent.wetype.plugin.hld.utils.m1\$f")
                    ?: error("Failed to load m1\$f")
                helperClass.declaredMethods.filter { it.name == "h" }.forEach { method ->
                    method.hookAfter { param ->
                        if (param.args.none { it is Int && it in popupAccentColorIds }) {
                            return@hookAfter
                        }
                        val fromPopup = Throwable().stackTrace.any { stackFrame ->
                            stackFrame.className.startsWith("com.tencent.wetype.plugin.hld.floatview")
                        }
                        if (!fromPopup) return@hookAfter
                        val group = WeTypeAppearanceColorGroups.findById(KEY_POPUP_BG_COLOR_GROUP_ID)
                            ?: return@hookAfter
                        val color = WeTypeSettings.getAppearanceColorXposed(KEY_POPUP_BG_COLOR_GROUP_ID)
                        if (group.passThroughByDefault && color == group.defaultColor) return@hookAfter
                        param.result = popupColorWithAlpha(color)
                    }
                }
                Log.i("Success: Hook floatview color fetch")
            }.onFailure {
                Log.i("Failed: Hook floatview color fetch")
                Log.i(it)
            }
        }
        // 辅通道:条目绑定视图背景可染时按选中态染色(画布绘制的皮肤上 background 为空,自动跳过)。
        deferredPluginInstall("wetype.popup-switch-drawer", "com.tencent.wetype.plugin.hld.floatview.D") {
            runCatching {
                val dClass = loadClassOrNull("com.tencent.wetype.plugin.hld.floatview.D")
                    ?: error("Failed to load floatview.D")
                val eClass = loadClassOrNull("com.tencent.wetype.plugin.hld.floatview.E")
                val group = WeTypeAppearanceColorGroups.findById(KEY_POPUP_BG_COLOR_GROUP_ID)
                    ?: error("Missing popup color group")
                // D.V(E, I, Z):条目绑定方法,Z 为选中/当前态布尔。Z=true 的条目染弹出色,
                // Z=false(含视图回收复用)清除染色恢复宿主原背景。
                val bindMethod = dClass.getDeclaredMethod(
                    "V", eClass, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                )
                bindMethod.hookAfter { param ->
                    val color = WeTypeSettings.getAppearanceColorXposed(KEY_POPUP_BG_COLOR_GROUP_ID)
                    val passthrough = group.passThroughByDefault && color == group.defaultColor
                    val selected = param[2] as? Boolean ?: return@hookAfter
                    val item = param[0] ?: return@hookAfter
                    val rootView = runCatching {
                        item.javaClass.getMethod("a").apply { isAccessible = true }.invoke(item) as? View
                    }.getOrNull() ?: return@hookAfter
                    val effective = if (passthrough) null else popupColorWithAlpha(color)
                    applyItemPopupTint(rootView, effective.takeIf { selected })
                }
                // 兜底:宿主若用 View 选中态标记当前条目,同样按弹出色处理。
                View::class.java.getMethod(
                    "setSelected", Boolean::class.javaPrimitiveType
                ).hookAfter { param ->
                    val view = param.thisObject as? View ?: return@hookAfter
                    if (!view.javaClass.name.startsWith("com.tencent.wetype.plugin.hld.floatview")) {
                        return@hookAfter
                    }
                    val color = WeTypeSettings.getAppearanceColorXposed(KEY_POPUP_BG_COLOR_GROUP_ID)
                    val passthrough = group.passThroughByDefault && color == group.defaultColor
                    val selected = param[0] as? Boolean ?: return@hookAfter
                    applyItemPopupTint(view, if (passthrough) null else color.takeIf { selected })
                }
                Log.i("Success: Hook switch drawer popup color")
            }.onFailure {
                Log.i("Failed: Hook switch drawer popup color")
                Log.i(it)
            }
        }
    }

    /** 弹出色与独立不透明度滑杆合成:滑杆直接替换 alpha 通道,255 时不改动拾色器原值。 */
    private fun popupColorWithAlpha(base: Int): Int {
        val alpha = WeTypeSettings.getPopupBgAlphaXposed()
        return if (alpha >= 255) base
        else Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }


    /**
     * 条目根视图背景染色;null 清除恢复宿主原背景。
     * 皮肤会把品牌强调色的 alpha(可能被用户设为 0)烘进背景 drawable 的填充色,
     * SRC_ATOP 叠加会保留目标 alpha 导致染色不可见——所以按 drawable 类型直接改
     * 填充色(Gradient/Shape 保留圆角形状),仅未知类型退回叠加。
     */
    private fun applyItemPopupTint(view: View, color: Int?) {
        val background = view.background ?: return
        runCatching {
            val mutated = background.mutate()
            if (color == null) {
                mutated.colorFilter = null
                return
            }
            when (mutated) {
                is android.graphics.drawable.GradientDrawable -> mutated.setColor(color)
                is android.graphics.drawable.ShapeDrawable -> mutated.paint.color = color
                else -> {
                    mutated.setAlpha(255)
                    mutated.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
                }
            }
            view.invalidate()
        }
    }

    fun reconcileCurrentToolbarIconBackgrounds(rootViews: Collection<View>) {
        val containerId = runCatching {
            loadClassOrNull(WETYPE_ID_R_CLASS)
                ?.getField("custom_toolbar_item_container_view")?.getInt(null)
        }.getOrNull() ?: return
        rootViews.forEach { rootView ->
            forEachView(rootView) { view ->
                if (view.id != containerId) return@forEachView
                applyToolbarIconBgAlpha(view.background)
                applyToolbarIconBgAlpha(view.foreground)
                val tint = WeTypeSettings.getAppearanceColorXposed(
                    ICON_COLOR_GROUP_ID, view.resources
                )
                forEachView(view) { child ->
                    (child as? ImageView)?.setColorFilter(tint)
                }
            }
        }
    }


    /**
     * 按住按键上滑选字/数字的弹出面板背景。ime_alternative_key_bg(_dark) 是 R$attr(o 类)
     * 的属性:皮肤经 resolveAttribute 解析,产物要么是宿主 drawable 资源 id(resourceId≠0),
     * 要么是内联颜色(resourceId=0)。两条路都拦:
     * - 内联颜色:直接改写 TypedValue(与按键色同一机制);
     * - drawable id:登记到映射,getDrawable 家族命中后 SRC_ATOP 染色(圆角/alpha 保留)。
     * 「按键弹出面板背景色」组带透传语义,未自定义时全部放行。
     */
    fun hookPopupBackgroundTint() {
        runCatching {
            // o 类是 attr(浅色变体),r 类是 drawable(含 _dark 变体);两类都可能命中。
            // 插件 R 类可能晚于首次 getDrawable 回调才加载,lazyResourceIds 一次性求值会
            // 永久缓存空映射(日志 "unresolved at first callback" 的教训),所以这里用
            // 每次未命中都重试的解析。
            val popupIdCache = object {
                @Volatile var ids: Map<Int, Int>? = null
            }
            val popupIdLock = Any()
            val popupDrawableNames = mapOf(
                "ime_alternative_key_bg" to 1,
                "ime_alternative_key_bg_dark" to 1
            )
            fun popupDrawableIdsContain(resId: Int): Boolean {
                popupIdCache.ids?.let { return resId in it }
                synchronized(popupIdLock) {
                    popupIdCache.ids?.let { return resId in it }
                    val resolved = resolveResourceIds(
                        popupDrawableNames,
                        "com.tencent.wetype.plugin.hld.r",
                        WETYPE_ID_R_CLASS
                    )
                    if (resolved.isNotEmpty()) popupIdCache.ids = resolved
                    return resolved.containsKey(resId)
                }
            }
            // attr 路径与 drawable 路径同款可重试解析:首次回调时 o 类可能尚未加载,
            // lazyResourceIds 的一次性求值会永久缓存空映射。
            val popupAttrCache = object {
                @Volatile var ids: Map<Int, Int>? = null
            }
            val popupAttrLock = Any()
            fun popupAttrIdsContain(attrId: Int): Boolean {
                popupAttrCache.ids?.let { return attrId in it }
                synchronized(popupAttrLock) {
                    popupAttrCache.ids?.let { return attrId in it }
                    val resolved = resolveResourceIds(
                        mapOf("ime_alternative_key_bg" to 1),
                        WETYPE_ATTR_R_CLASS,
                        WETYPE_ID_R_CLASS
                    )
                    if (resolved.isNotEmpty()) popupAttrCache.ids = resolved
                    return resolved.containsKey(attrId)
                }
            }
            val trackedDrawableIds = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())

            fun popupColorOrNull(resources: Resources): Int? {
                val group = WeTypeAppearanceColorGroups.findById(KEY_POPUP_BG_COLOR_GROUP_ID)
                    ?: return null
                val color = WeTypeSettings.getAppearanceColorXposed(KEY_POPUP_BG_COLOR_GROUP_ID, resources)
                if (group.passThroughByDefault && color == group.defaultColor) return null
                return color
            }

            Resources.Theme::class.java.getMethod(
                "resolveAttribute",
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                if (param.result != true) return@hookAfter
                val attrId = param[0] as? Int ?: return@hookAfter
                if (!popupAttrIdsContain(attrId)) return@hookAfter
                val outValue = param[1] as? TypedValue ?: return@hookAfter
                val resources = (param.thisObject as? Resources.Theme)?.resources ?: return@hookAfter
                val color = popupColorOrNull(resources) ?: return@hookAfter
                if (outValue.resourceId != 0) {
                    val typeName = runCatching { resources.getResourceTypeName(outValue.resourceId) }
                        .getOrNull()
                    if (typeName == "color") {
                        runtimeColorOverrides[outValue.resourceId] = WeTypeAppearanceColorGroups.findById(
                            KEY_POPUP_BG_COLOR_GROUP_ID
                        ) ?: return@hookAfter
                    } else {
                        trackedDrawableIds.add(outValue.resourceId)
                    }
                } else if (outValue.type >= TypedValue.TYPE_FIRST_INT && outValue.type <= TypedValue.TYPE_LAST_INT) {
                    applyColorToTypedValue(outValue, color)
                }
            }

            fun tintDrawableParam(resources: Resources, resId: Int, drawable: Drawable?): Drawable? {
                if (popupDrawableIdsContain(resId) || resId in trackedDrawableIds) {
                    val color = popupColorOrNull(resources) ?: return null
                    return drawable?.mutate()?.apply { setColorFilter(color, PorterDuff.Mode.SRC_ATOP) }
                }
                return null
            }

            Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param[0] as? Int ?: return@hookAfter
                    tintDrawableParam(resources, resId, param.result as? Drawable)
                        ?.also { param.result = it }
                }
            Resources::class.java.getMethod(
                "getDrawable", Int::class.javaPrimitiveType, Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val resId = param[0] as? Int ?: return@hookAfter
                tintDrawableParam(resources, resId, param.result as? Drawable)
                    ?.also { param.result = it }
            }
            Log.i("Success: Hook popup background tint")
        }.onFailure {
            Log.i("Failed: Hook popup background tint")
            Log.i(it)
        }
    }

    /** 工具栏图标底可能由 background 或 foreground 承载,统一读取当前配置后落 alpha。 */
    private fun applyToolbarIconBgAlpha(drawable: Drawable?) {
        drawable?.alpha = WeTypeSettings.getToolbarIconBgOpacityXposed()
    }

    fun hookToolbarIconBackground() {
        deferredPluginInstall("wetype.toolbar-icon", WETYPE_ID_R_CLASS) {
            runCatching {
                val sClass = loadClassOrNull(WETYPE_ID_R_CLASS)
                    ?: error("Failed to load plugin R\$id class")
                val containerId = sClass.getField("custom_toolbar_item_container_view").getInt(null)

                listOf("setBackground", "setBackgroundDrawable", "setForeground").forEach { method ->
                    View::class.java.getMethod(method, Drawable::class.java).hookBefore { param ->
                        val view = param.thisObject as? View ?: return@hookBefore
                        if (view.id != containerId) return@hookBefore
                        applyToolbarIconBgAlpha(param[0] as? Drawable)
                    }
                }
                Log.i("Success: Hook WeType toolbar icon background")
            }.onFailure {
                Log.i("Failed: Hook WeType toolbar icon background")
                Log.i(it)
            }
        }
    }

    private fun resolveCandidateItemRootClass(scrollViewClass: Class<*>): Class<*> =
        scrollViewClass.declaredClasses.firstOrNull { nestedClass ->
            nestedClass.superclass?.name?.startsWith(CANDIDATE_SELF_VIEW_PACKAGE) == true &&
                nestedClass.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.contentEquals(arrayOf(Context::class.java))
                } &&
                nestedClass.declaredFields.any { field ->
                    field.type.enclosingClass == scrollViewClass
                }
        } ?: error("Failed to resolve candidate item root class")

    private fun resolveCandidateSpecialTextColorMethod(): Method {
        val candidateViewClass = loadClassOrNull(CANDIDATE_VIEW_CLASS)
            ?: error("Failed to load ImeCandidateView")
        val candidateClass = candidateViewClass.declaredMethods
            .asSequence()
            .map { it.returnType }
            .distinct()
            .firstOrNull { returnType ->
                returnType.superclass?.name == "com.tencent.wxhld.info.Candidate" &&
                    resolveCandidateColorCompanionMethod(returnType) != null
            }
            ?: error("Failed to resolve CandidateWithExtra")
        return resolveCandidateColorCompanionMethod(candidateClass)
            ?: error("Failed to resolve CandidateWithExtra color method")
    }

    private fun resolveCandidateColorCompanionMethod(candidateClass: Class<*>): Method? {
        val staticHolderTypes = candidateClass.declaredFields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .map { it.type }
            .toSet()
        return candidateClass.declaredClasses
            .asSequence()
            .filter { it in staticHolderTypes }
            .firstNotNullOfOrNull { nestedClass ->
                val colorMethods = nestedClass.declaredMethods.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Int::class.javaPrimitiveType &&
                        method.parameterTypes.contentEquals(
                            arrayOf(Int::class.javaPrimitiveType)
                        )
                }
                val noArgColorMethodCount = nestedClass.declaredMethods.count { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Int::class.javaPrimitiveType &&
                        method.parameterTypes.isEmpty()
                }
                colorMethods.singleOrNull()
                    ?.takeIf { noArgColorMethodCount >= 2 }
                    ?.also { it.isAccessible = true }
            }
    }

    private fun resolveCandidateSelfViewBaseClass(): Class<*> {
        val scrollViewClass = loadClassOrNull(CANDIDATE_SCROLL_VIEW_CLASS)
            ?: error("Failed to load candidate scroll view")
        val itemRootClass = resolveCandidateItemRootClass(scrollViewClass)
        return generateSequence(itemRootClass as Class<*>?) { it.superclass }
            .firstOrNull { candidateClass ->
                candidateClass.name.startsWith(CANDIDATE_SELF_VIEW_PACKAGE) &&
                    runCatching { resolveCandidateBackgroundDrawMethod(candidateClass) }.isSuccess
            }
            ?: error("Failed to resolve candidate self view base class")
    }

    private fun resolveCandidateBackgroundDrawMethod(owner: Class<*>): Method = owner.findMethod {
        Modifier.isPrivate(modifiers) &&
            returnType == Void.TYPE &&
            parameterTypes.size == 3 &&
            parameterTypes[0] == Context::class.java &&
            parameterTypes[1] == Canvas::class.java &&
            parameterTypes[2] == Rect::class.java
    }

    private fun resolveKotlinPropertySetter(
        owner: Class<*>,
        propertyName: String
    ): Method? {
        owner.declaredMethods.firstOrNull { method ->
            method.name == "set${propertyName.replaceFirstChar { it.uppercase() }}" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }?.let { method ->
            method.isAccessible = true
            return method
        }

        val metadata = owner.declaredAnnotations.firstOrNull { annotation ->
            annotation.annotationClass.java.name == "kotlin.Metadata"
        } ?: return null
        val data2 = runCatching {
            @Suppress("UNCHECKED_CAST")
            metadata.annotationClass.java.getMethod("d2").invoke(metadata) as Array<String>
        }.getOrNull() ?: return null
        val propertyIndex = data2.indexOf(propertyName)
        if (propertyIndex < 0) return null

        return (propertyIndex - 1 downTo maxOf(0, propertyIndex - 4))
            .asSequence()
            .map { data2[it] }
            .flatMap { methodName ->
                owner.declaredMethods.asSequence().filter { method ->
                    method.name == methodName &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }
            }
            .firstOrNull()
            ?.also { it.isAccessible = true }
    }

    private fun applyCandidateBackgroundLeftMargin(
        itemRoot: Any,
        position: Int,
        setInsetMethod: Method,
        contextMethod: Method
    ) {
        if (position < 0) return
        val baseLeftPaddingPx = candidateItemRootBaseLeftPaddingPx[itemRoot] ?: 0
        val context = runCatching { contextMethod.invoke(itemRoot) as? Context }.getOrNull()
            ?: return
        val targetLeftPaddingPx = baseLeftPaddingPx + if (position == 0) {
            resolveCandidateBackgroundLeftMarginPx(context)
        } else {
            0
        }
        if (candidateItemRootAppliedLeftPaddingPx[itemRoot] == targetLeftPaddingPx) return

        candidateMarginInsetWriteDepth.set((candidateMarginInsetWriteDepth.get() ?: 0) + 1)
        try {
            setInsetMethod.invoke(itemRoot, targetLeftPaddingPx, null, null, null)
            candidateItemRootAppliedLeftPaddingPx[itemRoot] = targetLeftPaddingPx
        } finally {
            candidateMarginInsetWriteDepth.set((candidateMarginInsetWriteDepth.get() ?: 1) - 1)
        }
    }

    private fun ensureCandidatePinyinMarginSync(view: View) {
        if (candidatePinyinMarginListeners.containsKey(view)) return
        val layoutListener = View.OnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            applyCandidatePinyinLeftMargin(changedView)
        }
        val attachStateListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                applyCandidatePinyinLeftMargin(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                candidatePinyinMarginListeners.remove(v)?.let { listeners ->
                    v.removeOnLayoutChangeListener(listeners.layout)
                }
                v.removeOnAttachStateChangeListener(this)
            }
        }
        view.addOnLayoutChangeListener(layoutListener)
        view.addOnAttachStateChangeListener(attachStateListener)
        candidatePinyinMarginListeners[view] = CandidatePinyinListeners(
            layout = layoutListener,
            attach = attachStateListener
        )
    }

    /**
     * Releases view listeners, restores host logo drawables, and clears per-generation caches
     * before API 102 hot reload retires this module generation.
     */
    fun prepareForHotReload() {
        synchronized(candidatePinyinMarginListeners) {
            candidatePinyinMarginListeners.forEach { (view, listeners) ->
                runCatching { view.removeOnLayoutChangeListener(listeners.layout) }
                runCatching { view.removeOnAttachStateChangeListener(listeners.attach) }
            }
            candidatePinyinMarginListeners.clear()
        }
        synchronized(candidatePinyinOriginalPaddings) {
            candidatePinyinOriginalPaddings.forEach { (view, padding) ->
                runCatching {
                    view.setPaddingRelative(
                        padding.start,
                        padding.top,
                        padding.end,
                        padding.bottom
                    )
                }
            }
            candidatePinyinOriginalPaddings.clear()
        }

        val logoStates = synchronized(replacedLogoStates) {
            replacedLogoStates.entries.toList().also { replacedLogoStates.clear() }
        }
        restoringLogoDrawable.set(true)
        try {
            logoStates.forEach { (imageView, state) ->
                runCatching {
                    state.resourceId?.let(imageView::setImageResource)
                        ?: imageView.setImageDrawable(state.drawable)
                }
            }
        } finally {
            restoringLogoDrawable.remove()
        }

        synchronized(typedArrayAttributeCache) { typedArrayAttributeCache.clear() }
        synchronized(resourcePackageCache) { resourcePackageCache.clear() }
        synchronized(candidateItemRootBaseLeftPaddingPx) { candidateItemRootBaseLeftPaddingPx.clear() }
        synchronized(candidateItemRootAppliedLeftPaddingPx) {
            candidateItemRootAppliedLeftPaddingPx.clear()
        }
        synchronized(appearanceColorParamsLock) {
            appearanceColorCache = null
        }
        candidateMarginInsetWriteDepth.remove()
        hsvScratchThreadLocal.remove()
    }

    private fun applyCandidatePinyinLeftMargin(view: View) {
        synchronized(candidatePinyinOriginalPaddings) {
            candidatePinyinOriginalPaddings.getOrPut(view) {
                ViewPadding(view.paddingStart, view.paddingTop, view.paddingEnd, view.paddingBottom)
            }
        }
        val startPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCandidatePinyinLeftMarginDpXposed().toFloat(),
            view.resources.displayMetrics
        ).roundToInt()
        if (view.paddingStart == startPadding) return
        view.setPaddingRelative(
            startPadding,
            view.paddingTop,
            view.paddingEnd,
            view.paddingBottom
        )
    }

    private fun resolveCandidateBackgroundLeftMarginPx(context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCandidateBackgroundLeftMarginDpXposed().toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
    }

    private fun replaceColor(
        colorResId: Int,
        color: Int,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Int {
        staticColorReplacements[colorResId]?.let { return it }
        val group = dynamicColorReplacements[colorResId]
            ?: runtimeColorOverrides[colorResId]
            ?: return color
        return resolvedGroupColor(group, color)
    }

    private fun replaceTypedValue(
        typedValue: TypedValue,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val colorResId = typedValue.resourceId.takeIf { it != 0 } ?: return false
        val replacementColor = staticColorReplacements[colorResId]
            ?: dynamicColorReplacements[colorResId]?.let { group ->
                resolvedGroupColor(group, typedValue.data)
            }
            ?: runtimeColorOverrides[colorResId]?.let { group ->
                resolvedGroupColor(group, typedValue.data)
            }
            ?: return false

        typedValue.type = when (Color.alpha(replacementColor)) {
            0xFF -> TypedValue.TYPE_INT_COLOR_RGB8
            else -> TypedValue.TYPE_INT_COLOR_ARGB8
        }
        typedValue.data = replacementColor
        typedValue.assetCookie = 0
        typedValue.resourceId = 0
        typedValue.string = null
        return true
    }

    private fun replaceDrawable(
        resources: Resources,
        drawableResId: Int,
        theme: Resources.Theme?,
        drawableReplacements: Map<Int, Int>,
        getModuleResources: (Resources) -> Resources
    ): Drawable? {
        val replacementResId = drawableReplacements[drawableResId] ?: return null
        val replacementDrawable = getModuleResources(resources).getDrawable(replacementResId, null)
        return replacementDrawable.constantState?.newDrawable(resources, theme)?.mutate()
            ?: replacementDrawable.mutate()
    }

    private fun replaceColorStateList(
        colorResId: Int,
        colorStateList: ColorStateList,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): ColorStateList {
        val replacedColor = replaceColor(colorResId, colorStateList.defaultColor, staticColorReplacements, dynamicColorReplacements)
        if (replacedColor == colorStateList.defaultColor) return colorStateList
        return ColorStateList.valueOf(replacedColor)
    }

    private fun shouldReplaceDrawable(
        resources: Resources,
        drawableResId: Int,
        drawableReplacements: Map<Int, Int>
    ): Boolean = drawableResId in drawableReplacements && isWeTypeResource(resources, drawableResId)

    private fun shouldReplaceColor(
        resources: Resources,
        colorResId: Int,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean = isTargetColorResource(
        colorResId,
        staticColorReplacements,
        dynamicColorReplacements
    ) && isWeTypeResource(resources, colorResId)

    private fun shouldReplaceTypedValue(
        resources: Resources,
        typedValue: TypedValue,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean = isTargetWeTypeTypedValue(
        resources,
        typedValue,
        staticColorReplacements,
        dynamicColorReplacements
    )

    private fun isTargetWeTypeTypedValue(
        resources: Resources,
        typedValue: TypedValue,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val colorResId = typedValue.resourceId.takeIf { it != 0 } ?: return false
        return isTargetColorResource(colorResId, staticColorReplacements, dynamicColorReplacements) &&
            isWeTypeResource(resources, colorResId)
    }

    private fun isTargetColorResource(
        colorResId: Int,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean = colorResId in staticColorReplacements ||
        colorResId in dynamicColorReplacements ||
        runtimeColorOverrides.containsKey(colorResId)

    private fun hookThemeStyledAttributes(
        resolvedThemeAttrs: Lazy<Map<Int, WeTypeAppearanceColorGroup>>
    ) {
        // TypedArray instances return to a Resources pool; weak keys alone do not
        // prevent an old attribute mapping from leaking into the next borrower.
        TypedArray::class.java.getMethod("recycle").hookBefore { param ->
            typedArrayAttributeCache.remove(param.thisObject)
        }
        runCatching {
            Resources.Theme::class.java.getMethod("obtainStyledAttributes", IntArray::class.java)
                .hookAfter { param ->
                    val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                    val resources = theme.resources
                    val typedArray = param.result as? TypedArray ?: return@hookAfter
                    val attrs = param[0] as? IntArray ?: return@hookAfter
                    val themeAttrs = resolvedThemeAttrs.value
                    if (!containsTargetWeTypeThemeAttr(resources, attrs, themeAttrs)) return@hookAfter
                    typedArrayAttributeCache[typedArray] = attrs.copyOf()
                }
        }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "obtainStyledAttributes",
                Int::class.javaPrimitiveType,
                IntArray::class.java
            ).hookAfter { param ->
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val resources = theme.resources
                val typedArray = param.result as? TypedArray ?: return@hookAfter
                val attrs = param[1] as? IntArray ?: return@hookAfter
                val themeAttrs = resolvedThemeAttrs.value
                if (!containsTargetWeTypeThemeAttr(resources, attrs, themeAttrs)) return@hookAfter
                typedArrayAttributeCache[typedArray] = attrs.copyOf()
            }
        }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "obtainStyledAttributes",
                android.util.AttributeSet::class.java,
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).hookAfter { param ->
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val resources = theme.resources
                val typedArray = param.result as? TypedArray ?: return@hookAfter
                val attrs = param[1] as? IntArray ?: return@hookAfter
                val themeAttrs = resolvedThemeAttrs.value
                if (!containsTargetWeTypeThemeAttr(resources, attrs, themeAttrs)) return@hookAfter
                typedArrayAttributeCache[typedArray] = attrs.copyOf()
            }
        }
    }

    private fun replaceThemeAttributeTypedValue(
        attrResId: Int,
        typedValue: TypedValue,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val group = resolvedThemeAttrs[attrResId] ?: return false
        val replacementColor = resolvedGroupColor(group, typedValue.data)
        typedValue.type = when (Color.alpha(replacementColor)) {
            0xFF -> TypedValue.TYPE_INT_COLOR_RGB8
            else -> TypedValue.TYPE_INT_COLOR_ARGB8
        }
        typedValue.data = replacementColor
        typedValue.assetCookie = 0
        typedValue.resourceId = 0
        typedValue.string = null
        return true
    }

    private fun replaceThemeAttributeColor(
        typedArray: TypedArray, 
        index: Int,
        color: Int,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Int? {
        val attrIds = typedArrayAttributeCache[typedArray] ?: return null
        val attrResId = attrIds.getOrNull(index) ?: return null
        val group = resolvedThemeAttrs[attrResId] ?: return null
        return resolvedGroupColor(group, color)
    }

    private fun resolvedGroupColor(group: WeTypeAppearanceColorGroup, sourceColor: Int): Int {
        val params = appearanceColorParams(group)
        // 未自定义透传组（如按键文字色）保持宿主原色，避免默认值把原始配色染成品牌绿。
        if (group.passThroughByDefault && params.userColor == group.defaultColor) return sourceColor
        return when (params.mode) {
            WeTypeAppearanceColorMode.Direct -> params.userColor
            WeTypeAppearanceColorMode.HueShift -> tintToAccent(
                sourceColor = sourceColor,
                params = params
            )
        }
    }

    private fun appearanceColorParams(group: WeTypeAppearanceColorGroup): AppearanceColorParams {
        val appearanceColors = WeTypeSettings.getAppearanceColorsXposed()
        val cached = appearanceColorCache
        if (appearanceColors === cached?.colors) {
            cached.params[group.id]?.let { return it }
        }

        synchronized(appearanceColorParamsLock) {
            var cache = appearanceColorCache
            if (appearanceColors !== cache?.colors) {
                cache = AppearanceColorCache(appearanceColors, buildAppearanceColorParams(appearanceColors))
                appearanceColorCache = cache
            }
            checkNotNull(cache).params[group.id]?.let { return it }

            val params = buildAppearanceColorParam(
                group = group,
                userColor = appearanceColors[group.id] ?: group.defaultColor
            )
            appearanceColorCache = cache.copy(params = cache.params + (group.id to params))
            return params
        }
    }

    private fun buildAppearanceColorParams(
        appearanceColors: Map<String, Int>
    ): Map<String, AppearanceColorParams> {
        return WeTypeAppearanceColorGroups.groups.associate { group ->
            group.id to buildAppearanceColorParam(
                group = group,
                userColor = appearanceColors[group.id] ?: group.defaultColor
            )
        }
    }

    private fun buildAppearanceColorParam(
        group: WeTypeAppearanceColorGroup,
        userColor: Int
    ): AppearanceColorParams {
        if (group.colorMode == WeTypeAppearanceColorMode.Direct) {
            return AppearanceColorParams(
                mode = group.colorMode,
                userColor = userColor
            )
        }

        val defaultHsv = FloatArray(3)
        val targetHsv = FloatArray(3)
        Color.colorToHSV(group.defaultColor, defaultHsv)
        Color.colorToHSV(userColor, targetHsv)
        return AppearanceColorParams(
            mode = group.colorMode,
            userColor = userColor,
            targetHue = targetHsv[0],
            deltaSaturation = targetHsv[1] - defaultHsv[1],
            deltaValue = targetHsv[2] - defaultHsv[2]
        )
    }

    private fun tintToAccent(sourceColor: Int, params: AppearanceColorParams): Int {
        val sourceHsv = obtainHsvScratch()
        Color.colorToHSV(sourceColor, sourceHsv)

        sourceHsv[0] = params.targetHue
        sourceHsv[1] = (sourceHsv[1] + params.deltaSaturation).coerceIn(0f, 1f)
        sourceHsv[2] = (sourceHsv[2] + params.deltaValue).coerceIn(0f, 1f)
        val shifted = Color.HSVToColor(Color.alpha(sourceColor), sourceHsv)
        // 用户降低品牌强调色的不透明度时,与资源原 alpha 按比例叠加(255 时结果不变),
        // 否则拾色器里的透明度滑杆对 HueShift 资源完全不生效。
        val userAlpha = Color.alpha(params.userColor)
        return if (userAlpha == 0xFF) shifted else withForcedAlpha(shifted, Color.alpha(shifted) * userAlpha / 255)
    }

    private fun obtainHsvScratch(): FloatArray {
        return hsvScratchThreadLocal.get()!!
    }

    private fun containsTargetWeTypeThemeAttr(
        resources: Resources,
        attrs: IntArray,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        return attrs.any { attrResId ->
            isTargetWeTypeThemeAttr(resources, attrResId, resolvedThemeAttrs)
        }
    }

    private fun isTargetWeTypeThemeAttr(
        resources: Resources,
        attrResId: Int,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        return attrResId in resolvedThemeAttrs && isWeTypeResource(resources, attrResId)
    }

    private fun isWeTypeResource(resources: Resources, resId: Int): Boolean {
        if (resId == 0) return false
        val cache = synchronized(resourcePackageCache) {
            resourcePackageCache.getOrPut(resources) { mutableMapOf() }
        }
        synchronized(cache) {
            cache[resId]
        }?.let { return it }

        val isWeTypeResource = runCatching {
            resources.getResourcePackageName(resId) == WETYPE_RESOURCE_PACKAGE
        }.getOrDefault(false)
        synchronized(cache) {
            cache[resId] = isWeTypeResource
        }
        return isWeTypeResource
    }

    private fun withForcedAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
