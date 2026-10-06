package com.aurix.agent.core.agent

import com.aurix.agent.core.tools.device.foldText
import org.json.JSONObject
import java.util.Calendar

data class RoutedCommand(val tool: String, val input: JSONObject, val title: String)

/**
 * Offline, keyless command router for simple single-action phone commands in English/Hindi/Hinglish.
 * Anything multi-step, ambiguous or unknown returns null and goes to the AI planner instead.
 */
object IntentRouter {
    private val joiners = setOf("and", "then", "aur", "phir", "fir", "also")
    private val offWords = setOf("off", "band", "bandh", "bujhao", "bujha", "disable", "stop", "kardo")
    private val hindiNumbers = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "che" to 6, "chhah" to 6,
        "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12,
    )

    fun route(text: String, nowHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): RoutedCommand? {
        val raw = text.trim().lowercase()
        if (raw.isEmpty() || raw.length > 100) return null
        val t = foldText(raw)
        val w = t.split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty() || w.size > 12) return null
        if (w.any { it in joiners } || t.contains("uske baad")) return null
        return flashlight(w) ?: timerOrAlarm(raw, t, w, nowHour) ?: volume(raw, w) ?: screenKey(t, w) ?: scroll(w) ?: battery(w)
            ?: location(t) ?: storage(w) ?: media(w) ?: callNumber(raw) ?: navigate(t, w) ?: playMusic(t, w) ?: openApp(t, w)
    }

    private fun cmd(tool: String, title: String, vararg kv: Pair<String, Any>) =
        RoutedCommand(tool, JSONObject().also { j -> kv.forEach { (k, v) -> j.put(k, v) } }, title)

    private fun flashlight(w: List<String>): RoutedCommand? {
        val has = w.any { it == "flashlight" || it == "torch" || it == "tourch" || it == "tarch" } || ("flash" in w && "light" in w)
        if (!has) return null
        val off = w.any { it in offWords }
        return cmd("FLASHLIGHT", if (off) "Flashlight off" else "Flashlight on", "on" to !off)
    }

    private val unit = Regex("(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|sec|minutes?|mins?|min|hours?|hrs?|hr|ghanta|ghante|ghanta)\\b")

    private fun seconds(raw: String): Int? {
        val m = unit.find(raw) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val u = m.groupValues[2]
        val mult = when { u.startsWith("sec") -> 1; u.startsWith("min") -> 60; else -> 3600 }
        val s = (n * mult).toInt()
        return if (s in 1..86_400) s else null
    }

    private fun parseClock(raw: String, w: List<String>, nowHour: Int): Pair<Int, Int>? {
        var h: Int? = null; var min = 0; var meridiem: String? = null
        Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(raw)?.let {
            h = it.groupValues[1].toInt(); min = it.groupValues[2].toInt(); meridiem = it.groupValues[3].ifEmpty { null }
        }
        if (h == null) Regex("\\b(\\d{1,2})\\s*(am|pm|baje|bje|o'?clock|oclock)\\b").find(raw)?.let {
            h = it.groupValues[1].toInt(); if (it.groupValues[2] == "am" || it.groupValues[2] == "pm") meridiem = it.groupValues[2]
        }
        if (h == null) {
            val i = w.indexOfFirst { it == "baje" || it == "bje" }
            if (i > 0) hindiNumbers[w[i - 1]]?.let { h = it }
        }
        val hh = h ?: return null
        if (min !in 0..59) return null
        val pmWords = w.any { it in setOf("shaam", "sham", "evening", "raat", "night", "dopahar", "afternoon") }
        val amWords = w.any { it in setOf("subah", "morning", "savere", "savera") }
        val tomorrow = w.any { it == "kal" || it == "tomorrow" }
        val mer = meridiem?.replace(".", "")
        return when {
            hh in 13..23 -> hh to min
            hh == 0 -> 0 to min
            mer == "am" || amWords -> (if (hh == 12) 0 else hh) to min
            mer == "pm" || pmWords -> (if (hh == 12) 12 else hh + 12) to min
            hh !in 1..12 -> null
            tomorrow -> (if (hh in 4..11) hh else if (hh == 12) 12 else hh + 12) to min
            else -> {
                val am = if (hh == 12) 0 else hh
                val pm = if (hh == 12) 12 else hh + 12
                // next occurrence of that clock hour from now
                (if (am > nowHour) am else if (pm > nowHour) pm else am) to min
            }
        }
    }

    private fun timerOrAlarm(raw: String, t: String, w: List<String>, nowHour: Int): RoutedCommand? {
        val isAlarm = "alarm" in w
        val isTimer = "timer" in w || "countdown" in w
        if (!isAlarm && !isTimer) return null
        if (w.any { it in setOf("cancel", "delete", "hata", "hatao", "remove") }) return null
        val relative = Regex("\\b(baad|mein|me|after|in)\\b").containsMatchIn(t) || isTimer
        val s = seconds(raw)
        if (s != null && (isTimer || relative)) {
            val title = if (s % 3600 == 0) "Timer ${s / 3600} h" else if (s % 60 == 0) "Timer ${s / 60} min" else "Timer $s s"
            return cmd("SET_TIMER", title, "seconds" to s)
        }
        if (isAlarm) {
            val c = parseClock(raw, w, nowHour) ?: return null
            return cmd("SET_ALARM", "Alarm %02d:%02d".format(c.first, c.second), "hour" to c.first, "minute" to c.second)
        }
        return null
    }

    private fun volume(raw: String, w: List<String>): RoutedCommand? {
        val has = w.any { it == "volume" || it == "awaaz" || it == "awaz" || it == "sound" }
        if (!has && "mute" !in w) return null
        val num = Regex("\\b(\\d{1,3})\\s*(%|percent|pratishat)?").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            "mute" in w || "silent" in w -> cmd("SET_VOLUME", "Mute", "level" to 0)
            num != null && num in 0..100 -> cmd("SET_VOLUME", "Volume $num%", "level" to num)
            w.any { it in setOf("max", "full", "maximum", "poori", "puri") } -> cmd("SET_VOLUME", "Volume max", "level" to 100)
            w.any { it in setOf("badhao", "badha", "increase", "up", "zyada", "louder", "tez", "high") } -> cmd("SET_VOLUME", "Volume up", "delta" to 15)
            w.any { it in setOf("kam", "ghatao", "ghata", "decrease", "down", "lower", "dheere", "low") } -> cmd("SET_VOLUME", "Volume down", "delta" to -15)
            else -> null
        }
    }

    private fun screenKey(t: String, w: List<String>): RoutedCommand? {
        fun k(key: String, title: String) = cmd("SCREEN_KEY", title, "key" to key)
        return when {
            t in setOf("go back", "back", "back jao", "piche jao", "peeche jao", "wapas jao") -> k("back", "Go back")
            t in setOf("go home", "home screen", "home jao", "home par jao", "home pe jao", "go to home screen") -> k("home", "Go home")
            "recents" in w || t.contains("recent apps") || t.contains("app switcher") -> k("recents", "Recent apps")
            ("notification" in w || "notifications" in w) && w.any { it in setOf("kholo", "open", "panel", "shade", "dikhao", "show") } -> k("notifications", "Open notification shade")
            t.contains("quick settings") -> k("quick_settings", "Quick settings")
            t in setOf("lock screen", "lock phone", "phone lock karo", "screen lock karo", "lock the phone", "lock my phone", "lock karo") -> k("lock", "Lock screen")
            "screenshot" in w -> k("screenshot", "Screenshot")
            else -> null
        }
    }

    private fun scroll(w: List<String>): RoutedCommand? {
        if ("scroll" !in w) return null
        val dir = when {
            "up" in w || "upar" in w || "oopar" in w -> "up"
            "left" in w -> "left"
            "right" in w -> "right"
            else -> "down"
        }
        return cmd("SCREEN_SCROLL", "Scroll $dir", "direction" to dir)
    }

    private fun battery(w: List<String>): RoutedCommand? =
        if (("battery" in w || "charging" in w) && w.size <= 7) cmd("BATTERY_INFO", "Battery info") else null

    private fun location(t: String): RoutedCommand? =
        if (listOf("meri location", "my location", "where am i", "mai kahan hu", "main kahan hoon", "main kahan hu", "mai kahan hoon", "current location").any { t.contains(it) })
            cmd("MY_LOCATION", "My location") else null

    private fun storage(w: List<String>): RoutedCommand? =
        if ("storage" in w && w.any { it in setOf("kitni", "kitna", "bachi", "bacha", "left", "free", "info", "analyze", "analyse", "check", "space") })
            cmd("STORAGE_INFO", "Storage info") else null

    private val mediaFiller = setOf("song", "songs", "gaana", "gana", "track", "music", "please", "karo", "kar", "do", "the", "a", "kro", "play")

    private fun media(w: List<String>): RoutedCommand? {
        if (w.size > 3) return null
        val triggers = mapOf(
            "pause" to "pause", "ruko" to "pause", "ruk" to "pause",
            "next" to "next", "skip" to "next", "agla" to "next",
            "previous" to "previous", "prev" to "previous", "pichla" to "previous", "pichhla" to "previous",
            "resume" to "play", "chalao" to "play",
        )
        val hit = w.firstNotNullOfOrNull { triggers[it] }
        val a = when {
            hit != null && w.all { it in triggers || it in mediaFiller } -> hit
            w == listOf("play") || w == listOf("play", "karo") -> "play"
            else -> return null
        }
        return cmd("MEDIA_CONTROL", "Media: $a", "action" to a)
    }

    private fun callNumber(raw: String): RoutedCommand? {
        val m = Regex("^(?:call|dial|phone)\\s+(\\+?[0-9][0-9\\s\\-()]{5,})$").find(raw) ?: return null
        val n = m.groupValues[1].replace(Regex("[\\s\\-()]"), "")
        return cmd("CALL_PHONE", "Call $n", "number" to n)
    }

    private val navTail = listOf("ka rasta batao", "ka rasta", "ke liye navigate karo", "ke liye navigate", "navigate karo", "pe le chalo", "par le chalo", "le chalo")

    private fun navigate(t: String, w: List<String>): RoutedCommand? {
        var dest: String? = null
        for (p in listOf("navigate to ", "directions to ", "take me to ", "navigate ")) if (t.startsWith(p)) { dest = t.removePrefix(p); break }
        if (dest == null) for (s in navTail) if (t.endsWith(s)) { dest = t.removeSuffix(s); break }
        val d = dest?.trim().orEmpty()
        if (d.isEmpty() || d.split(' ').size > 6) return null
        return cmd("NAVIGATE", "Navigate to $d", "destination" to d)
    }

    private val musicFiller = setOf("song", "songs", "gaana", "gana", "gane", "gaane", "music", "the", "some")
    private val musicTail = listOf("bajao", "baja do", "chalao", "chala do", "sunao", "suna do", "play karo", "play kar do", "play kro", "bajaye")

    private fun playMusic(t: String, w: List<String>): RoutedCommand? {
        var q: String? = null
        if (t.startsWith("play ")) q = t.removePrefix("play ")
        else for (s in musicTail) if (t.endsWith(" $s")) { q = t.removeSuffix(" $s"); break }
        var query = q?.trim().orEmpty()
        if (query.isEmpty()) return null
        if (query == "store" || query == "play store") return null
        query = query.replace(Regex("\\b(on|pe|par|mein|me)\\s+(youtube|spotify|yt music)\\b"), "").replace(Regex("\\b(youtube|spotify)\\s+(pe|par|mein|me|on)\\b"), "").trim()
        val words = query.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.all { it in musicFiller }) return null
        return cmd("PLAY_MUSIC", "Play $query", "query" to query)
    }

    private val openTail = listOf("kholo", "khol do", "khol", "open karo", "open kar do", "open kro", "chalu karo", "launch karo")

    private fun openApp(t: String, w: List<String>): RoutedCommand? {
        var name: String? = null
        for (p in listOf("open ", "launch ")) if (t.startsWith(p)) { name = t.removePrefix(p); break }
        if (name == null) for (s in openTail) if (t.endsWith(" $s")) { name = t.removeSuffix(" $s"); break }
        val n = name?.removePrefix("the ")?.trim().orEmpty()
        val nw = n.split(' ').filter { it.isNotEmpty() }
        if (nw.isEmpty() || nw.size > 3) return null
        if (nw.any { it in setOf("link", "url", "website", "http", "https", "music", "song", "songs", "file", "folder") }) return null
        return cmd("OPEN_APP", "Open $n", "name" to n)
    }
}
