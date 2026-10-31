package x.vladgba.launcher.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper

/**
 * Day / night colours for everything Sheaf draws itself.
 * [night] is set by the activity from the "Color mode" setting
 * (system / day / night) and read whenever views bind or draw.
 */
object Palette {
    var night = true

    /** Resolves a color-mode setting ("system" | "day" | "night") to night = true/false. */
    fun resolve(ctx: Context, mode: String): Boolean = when (mode) {
        "day" -> false
        "night" -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    // Home screen (over the wallpaper)
    val label get() = if (night) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
    val labelShadow get() = if (night) 0xB0000000.toInt() else 0x99FFFFFF.toInt()
    val folderBg get() = if (night) 0x66FFFFFF else 0x40000000
    val dotOn get() = if (night) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()
    val dotOff get() = if (night) 0x66FFFFFF else 0x55000000

    // Panels (folder, widget picker): suite pUI surfaces (p_surface / p_text / p_text2 / p_accent_text)
    val scrim get() = if (night) 0x77000000 else 0x44000000
    val card get() = if (night) 0xF21C1F26.toInt() else 0xF5FFFFFF.toInt()
    val cardText get() = if (night) 0xFFECEDF1.toInt() else 0xFF16181D.toInt()
    val cardTextDim get() = if (night) 0xFFA3A7B3.toInt() else 0xFF5F6370.toInt()
    val accentText get() = if (night) 0xFFB197FC.toInt() else 0xFF6D28D9.toInt()
    val previewBg get() = if (night) 0x22FFFFFF else 0x14000000

    // Dialogs and popup menus: pUI themes, resolved in the launcher's own color mode
    // (which may differ from the system's), so the p_* tokens pick the matching palette.
    val dialogTheme get() = x.vladgba.launcher.R.style.Theme_P_Dialog

    /** Context whose resources use the chosen day/night mode, themed with pUI. */
    fun ui(ctx: Context, theme: Int = x.vladgba.launcher.R.style.Theme_P_Bar): Context {
        val cfg = Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return ContextThemeWrapper(ctx, theme).apply { applyOverrideConfiguration(cfg) }
    }

    /** Context for PopupMenus so they follow the chosen mode. */
    fun menuContext(ctx: Context): Context = ui(ctx)
}
