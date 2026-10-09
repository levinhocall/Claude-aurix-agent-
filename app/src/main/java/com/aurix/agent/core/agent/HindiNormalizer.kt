package com.aurix.agent.core.agent

private val words = mapOf(
    "फ्लैशलाइट" to "flashlight", "टॉर्च" to "flashlight", "टार्च" to "flashlight", "अलार्म" to "alarm", "टाइमर" to "timer",
    "वॉल्यूम" to "volume", "आवाज़" to "volume", "आवाज" to "volume", "ब्राइटनेस" to "brightness", "वाईफाई" to "wifi", "वाईफ़ाई" to "wifi",
    "ब्लूटूथ" to "bluetooth", "बैटरी" to "battery", "कॉल" to "call", "मैसेज" to "message", "संदेश" to "message", "खोलो" to "open",
    "खोल" to "open", "चालू" to "on", "बंद" to "off", "करो" to "karo", "कर" to "kar", "दो" to "do", "को" to "ko", "का" to "ka", "की" to "ki",
    "रास्ता" to "rasta", "बताओ" to "batao", "गाना" to "gaana", "बजाओ" to "play", "फोटो" to "photos", "कैमरा" to "camera", "कैमरे" to "camera",
    "घर" to "ghar", "ऑफिस" to "office", "पास" to "nearby", "मेरी" to "meri", "मेरा" to "mera", "भेजो" to "bhejo", "बढ़ाओ" to "badhao", "घटाओ" to "ghatao",
    "क्यों" to "kyun", "क्या" to "kya", "हुआ" to "hua", "फेल" to "fail", "स्क्रीनशॉट" to "screenshot", "बजे" to "baje", "सुबह" to "subah", "शाम" to "shaam",
)

/** Maps common Devanagari command words to the Roman Hinglish forms the offline router understands. */
fun normalizeHindi(s: String): String {
    if (s.none { it in 'ऀ'..'ॿ' }) return s
    val digits = s.map { if (it in '०'..'९') ('0' + (it - '०')) else it }.joinToString("")
    return digits.split(' ').joinToString(" ") { words[it] ?: it }
}
