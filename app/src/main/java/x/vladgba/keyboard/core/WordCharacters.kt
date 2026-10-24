package x.vladgba.keyboard.core

fun isLetterOrMark(ch: Char): Boolean = ch.isLetter() || when (Character.getType(ch)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}
