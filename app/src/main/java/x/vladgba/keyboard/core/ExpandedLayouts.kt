package x.vladgba.keyboard.core

object ExpandedLayouts {
    val names = setOf("ar", "he", "el", "tr", "it", "pt", "cs", "hu", "sv", "fi", "nb", "da")
    fun enabled(name: String) = name !in names || Settings.bool(SETTING_EXPANDED_LAYOUTS, false)
}
