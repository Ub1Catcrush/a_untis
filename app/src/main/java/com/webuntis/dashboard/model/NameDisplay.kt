package com.webuntis.dashboard.model

/** Screens whose subject/teacher/room names can be configured independently
 *  (Settings → "Klar- und Kurznamen"). [types] = what that screen actually shows. */
enum class NameScreen(val types: List<NameType>) {
    DAY_VIEW(listOf(NameType.SUBJECT, NameType.TEACHER, NameType.ROOM)),
    /** First line of a week-view tile: whichever of these is picked in Settings, written per its style. */
    WEEK_VIEW(listOf(NameType.SUBJECT, NameType.TEACHER, NameType.ROOM)),
    /** Second line of a week-view tile: whichever of these is picked in Settings, written per its style. */
    WEEK_VIEW_LINE2(listOf(NameType.SUBJECT, NameType.TEACHER, NameType.ROOM)),
    LESSON_CONTENT(listOf(NameType.SUBJECT, NameType.TEACHER)),
    CLASSBOOK(listOf(NameType.SUBJECT, NameType.TEACHER)),
    HOMEWORK(listOf(NameType.SUBJECT)),
    MESSAGES(listOf(NameType.TEACHER)),
    EVENTS(listOf(NameType.SUBJECT))
}

enum class NameType { SUBJECT, TEACHER, ROOM }

/** How one kind of name is rendered on one screen: short code, spelled-out ("long") name,
 *  or long name followed by the short code in parentheses ("Mathematik (M)"). */
data class NameStyle(val long: Boolean = false, val shortInParens: Boolean = false) {
    fun format(short: String?, longName: String?): String {
        val s = short?.trim().orEmpty()
        val l = longName?.trim()?.takeIf { it.isNotEmpty() }
        if (!long || l == null || l == s) return s.ifEmpty { l.orEmpty() }
        return if (shortInParens && s.isNotEmpty()) "$l ($s)" else l
    }

    companion object {
        /** Long name with the short code in parentheses — the pre-setting look of homework/messages/events. */
        val LONG_WITH_SHORT = NameStyle(long = true, shortInParens = true)
    }
}
