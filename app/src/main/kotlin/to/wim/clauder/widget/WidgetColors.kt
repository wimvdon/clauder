package to.wim.clauder.widget

import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import to.wim.clauder.ui.theme.DarkColors
import to.wim.clauder.ui.theme.LightColors

/** Clauder's own colours, or the wallpaper's when the user turned on Dynamic color. */
fun widgetColors(dynamic: Boolean): ColorProviders? =
    if (dynamic) null else ColorProviders(light = LightColors, dark = DarkColors)
