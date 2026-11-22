package com.xposed.wetypehook.wetype.settings

import androidx.annotation.StringRes
import com.xposed.wetypehook.R

const val LIGHT_KEY_COLOR_GROUP_ID = "transparent_h6"
const val DARK_KEY_COLOR_GROUP_ID = "transparent_l0"
const val CANDIDATE_COLOR_GROUP_ID = "candidate_color"
/**
 * 键盘左上角 logo 的前景（徽标）色。与 "theme_color" 刻意拆开：品牌色会 HueShift 染遍整个输入法，
 * 而只想改图标颜色时不应联动按键与候选栏；默认值同品牌色，保证旧版本升级后图标外观不变。
 */
const val ICON_COLOR_GROUP_ID = "icon_color"
/**
 * 按键文字（label）色。原先混在 "theme_color" 的 HueShift 列表里跟着品牌色走；拆出来独立调，
 * 但按键文字原始色并非品牌色系，默认值若像其他组一样直接生效会把所有按键文字染成品牌绿，
 * 因此该组带 passThroughByDefault：用户未改色时保持宿主原色。
 */
const val KEY_TEXT_COLOR_GROUP_ID = "key_text_color"
/**
 * 按住按键上滑选字/数字时的弹出面板背景（宿主形状 drawable，经 SRC_ATOP 染色）。
 * 原 drawable 引用品牌系颜色；passThroughByDefault 让未自定义时保持宿主观感不变。
 */
const val KEY_POPUP_BG_COLOR_GROUP_ID = "key_popup_bg_color"

enum class WeTypeAppearanceColorMode {
    Direct,
    HueShift
}

data class WeTypeAppearanceColorGroup(
    val id: String,
    val displayName: String,
    val defaultColor: Int,
    val colorResourceNames: Set<String> = emptySet(),
    val themeAttributeNames: Set<String> = emptySet(),
    val colorMode: WeTypeAppearanceColorMode = WeTypeAppearanceColorMode.Direct,
    /** true 时颜色值等于 defaultColor 视为未自定义，替换逻辑透传宿主原色。 */
    val passThroughByDefault: Boolean = false
) {
    val entryCount: Int
        get() = colorResourceNames.size + themeAttributeNames.size

    val isKeyColorGroup: Boolean
        get() = id == LIGHT_KEY_COLOR_GROUP_ID || id == DARK_KEY_COLOR_GROUP_ID
}

object WeTypeAppearanceColorGroups {
    val groups: List<WeTypeAppearanceColorGroup> = listOf(
        WeTypeAppearanceColorGroup(
            id = "theme_color",
            displayName = "品牌强调色",
            defaultColor = 0xFF23c891.toInt(),
            colorMode = WeTypeAppearanceColorMode.HueShift,
            colorResourceNames = setOf(
                "Brand", "Brand_100", "Brand_100_CARE", "Brand_120", "Brand_170",
                "Brand_80", "Brand_80_CARE", "Brand_90", "Brand_90_CARE",
                "Brand_Alpha_0_1", "Brand_Alpha_0_2", "Brand_Alpha_0_2_CARE",
                "Brand_Alpha_0_3", "Brand_Alpha_0_5", "Brand_BG_100",
                "Brand_BG_100_CARE", "Brand_BG_110", "Brand_BG_130", "Brand_BG_90",
                "Green", "Green_100", "Green_100_CARE", "Green_120", "Green_170",
                "Green_80", "Green_80_CARE", "Green_90", "Green_90_CARE",
                "Green_BG_100", "Green_BG_110", "Green_BG_130", "Green_BG_90",
                "LightGreen", "LightGreen_100", "LightGreen_100_CARE", "LightGreen_120",
                "LightGreen_170", "LightGreen_80", "LightGreen_80_CARE",
                "LightGreen_90", "LightGreen_90_CARE", "LightGreen_BG_100",
                "LightGreen_BG_110", "LightGreen_BG_130", "LightGreen_BG_90",
                "abc_search_url_text_normal", "btn_green_text_color", "ime_candidate_content_select_bg_color_dark",
                "ime_candidate_content_select_bg_color_light", "ime_candidate_syllable_select_bg_color_dark", "ime_candidate_syllable_select_bg_color_light",
                "ime_color_14_Alpha_10", "ime_color_14_Alpha_10_light",
                "ime_color_14_Alpha_20", "ime_color_14_Alpha_20_light",
                "ime_color_14_Alpha_30", "ime_color_14_Alpha_30_light",
                "ime_color_14_Alpha_50_dark", "ime_color_14_Alpha_50_light",
                "ime_color_14_dark", "ime_color_14_light",
                "ime_skin_candidate_content_select_bg_color", "ime_skin_color_14", "ime_skin_color_14_Alpha_20",
                "ime_skin_color_14_Alpha_50", "ime_skin_color_19", "ime_skin_color_divider", "ime_skin_color_emoji_enter_btn_bg_disabled", "ime_skin_dark_Brand_90",
                "ime_skin_dark_candidate_content_select_bg_color", "ime_skin_dark_color_14", "ime_skin_dark_color_14_Alpha_20", "ime_skin_dark_color_14_Alpha_50",
                "ime_skin_dark_color_emoji_enter_btn_bg_disabled", "level_best_color", "level_middle_color", "level_normal_color", "material_deep_teal_500", "toasterro"
            )
        ),
        WeTypeAppearanceColorGroup(
            id = CANDIDATE_COLOR_GROUP_ID,
            displayName = "候选词强调色",
            defaultColor = 0xFF23c891.toInt(),
            // 候选栏首个候选词的强调色。4.0.x 混淆名为 lg(=#FF00E38D),插件 R 类里只存在
            // 原名 ime_skin_dark_color_19;混淆名随宿主版本漂移,统一用原名解析。
            colorResourceNames = setOf("ime_skin_dark_color_19")
        ),
        WeTypeAppearanceColorGroup(
            id = ICON_COLOR_GROUP_ID,
            displayName = "键盘图标颜色",
            defaultColor = 0xFF23c891.toInt()
        ),
        WeTypeAppearanceColorGroup(
            id = KEY_TEXT_COLOR_GROUP_ID,
            displayName = "按键文字颜色",
            defaultColor = 0xFF23c891.toInt(),
            colorResourceNames = setOf(
                "ime_skin_color_btn_white_text", "ime_skin_color_btn_white_text_pressed",
                "ime_key_back_icon_color",
                // 侧边栏/功能条文字(123/#+=/中英/退格标签):旧映射用混淆名 lc/ld,宿主
                // 热更新后插件 R 类只剩原名,解析整体失效(日志 "Failed to resolve resource
                // IDs for: [lg, lc, ld]" 实锤)。原名 #ff050505 浅色字/#21ffffff 深色字;
                // ime_color_00_dark/light(#ffffffff/#ff000000)为同语义深浅槽位,一并兜住。
                "ime_skin_dark_color_15", "ime_skin_dark_color_16",
                "ime_color_00_dark", "ime_color_00_light"
            ),
            passThroughByDefault = true
        ),
        WeTypeAppearanceColorGroup(
            id = KEY_POPUP_BG_COLOR_GROUP_ID,
            displayName = "按键弹出面板背景色",
            defaultColor = 0xFF23c891.toInt(),
            passThroughByDefault = true
        ),
        WeTypeAppearanceColorGroup(
            id = LIGHT_KEY_COLOR_GROUP_ID,
            displayName = "浅色模式按键色",
            defaultColor = 0xABFFFFFF.toInt(),
            // Scope to the self-draw key fill resources only: normal/QWERTY keys ("white") and
            // special/function keys ("grey"). Resolved by stable R.color field names so it stays
            // version-agnostic, and deliberately excludes keys backed by other colors (e.g. the
            // theme-colored "搜索"/enter key) and unrelated non-key surfaces.
            colorResourceNames = setOf("ime_skin_key_white_color", "ime_skin_key_grey_color")
        ),
        WeTypeAppearanceColorGroup(
            id = DARK_KEY_COLOR_GROUP_ID,
            displayName = "深色模式按键色",
            defaultColor = 0x2BEDEDED,
            colorResourceNames = setOf("ime_skin_dark_key_white_color", "ime_skin_dark_key_grey_color")
        )
    )

    val obsoleteGroupIds: Set<String> = setOf(
        "transparent",
        "dark_gray",
        "accent_blue",
        "accent_blue_overlay",
        "medium_gray",
        "pale_blue",
        "pale_surface",
        "off_white",
        "white"
    )

    private val groupsById = groups.associateBy { it.id }
    private val colorNameToGroup = groups.flatMap { group ->
        group.colorResourceNames.map { name -> name to group }
    }.toMap()
    private val themeAttributeToGroup = groups.flatMap { group ->
        group.themeAttributeNames.map { name -> name to group }
    }.toMap()

    fun defaultColors(): Map<String, Int> = groups.associate { it.id to it.defaultColor }

    fun findById(id: String): WeTypeAppearanceColorGroup? = groupsById[id]

    fun findByColorName(name: String): WeTypeAppearanceColorGroup? = colorNameToGroup[name]

    fun findByThemeAttributeName(name: String): WeTypeAppearanceColorGroup? = themeAttributeToGroup[name]
}
