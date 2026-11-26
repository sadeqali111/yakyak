package com.ovpn.autodelay

object CountryFlags {
    private val codeMap = mapOf(
        "ar" to "🇦🇷", "au" to "🇦🇺", "at" to "🇦🇹", "be" to "🇧🇪", "br" to "🇧🇷",
        "bg" to "🇧🇬", "ca" to "🇨🇦", "cl" to "🇨🇱", "co" to "🇨🇴", "cy" to "🇨🇾",
        "cz" to "🇨🇿", "dk" to "🇩🇰", "fi" to "🇫🇮", "fr" to "🇫🇷", "de" to "🇩🇪",
        "gr" to "🇬🇷", "hu" to "🇭🇺", "in" to "🇮🇳", "id" to "🇮🇩", "ie" to "🇮🇪",
        "it" to "🇮🇹", "jp" to "🇯🇵", "ke" to "🇰🇪", "lv" to "🇱🇻", "lt" to "🇱🇹",
        "my" to "🇲🇾", "mx" to "🇲🇽", "md" to "🇲🇩", "nl" to "🇳🇱", "nz" to "🇳🇿",
        "ng" to "🇳🇬", "no" to "🇳🇴", "pe" to "🇵🇪", "ph" to "🇵🇭", "pl" to "🇵🇱",
        "pt" to "🇵🇹", "ro" to "🇷🇴", "rs" to "🇷🇸", "sg" to "🇸🇬", "sk" to "🇸🇰",
        "si" to "🇸🇮", "za" to "🇿🇦", "kr" to "🇰🇷", "es" to "🇪🇸", "se" to "🇸🇪",
        "ch" to "🇨🇭", "tw" to "🇹🇼", "tr" to "🇹🇷", "ua" to "🇺🇦", "uk" to "🇬🇧",
        "gb" to "🇬🇧", "us" to "🇺🇸", "hk" to "🇭🇰", "ae" to "🇦🇪", "il" to "🇮🇱"
    )

    fun flagFor(host: String, name: String): String {
        val prefix = host.substringBefore(".").lowercase()
        codeMap[prefix]?.let { return it }
        val lower = name.lowercase()
        return when {
            "united states" in lower -> "🇺🇸"
            "united kingdom" in lower -> "🇬🇧"
            "netherlands" in lower -> "🇳🇱"
            "germany" in lower -> "🇩🇪"
            "france" in lower -> "🇫🇷"
            "finland" in lower -> "🇫🇮"
            "canada" in lower -> "🇨🇦"
            "moldova" in lower -> "🇲🇩"
            "sweden" in lower -> "🇸🇪"
            "switzerland" in lower -> "🇨🇭"
            "spain" in lower -> "🇪🇸"
            "italy" in lower -> "🇮🇹"
            "japan" in lower -> "🇯🇵"
            "singapore" in lower -> "🇸🇬"
            "australia" in lower -> "🇦🇺"
            "austria" in lower -> "🇦🇹"
            "argentina" in lower -> "🇦🇷"
            "belgium" in lower -> "🇧🇪"
            "brazil" in lower -> "🇧🇷"
            "bulgaria" in lower -> "🇧🇬"
            "chile" in lower -> "🇨🇱"
            "colombia" in lower -> "🇨🇴"
            "czech" in lower -> "🇨🇿"
            "denmark" in lower -> "🇩🇰"
            "greece" in lower -> "🇬🇷"
            "hungary" in lower -> "🇭🇺"
            "india" in lower -> "🇮🇳"
            "ireland" in lower -> "🇮🇪"
            "mexico" in lower -> "🇲🇽"
            "new zealand" in lower -> "🇳🇿"
            "norway" in lower -> "🇳🇴"
            "poland" in lower -> "🇵🇱"
            "portugal" in lower -> "🇵🇹"
            "romania" in lower -> "🇷🇴"
            "south korea" in lower -> "🇰🇷"
            "taiwan" in lower -> "🇹🇼"
            "ukraine" in lower -> "🇺🇦"
            else -> "🌐"
        }
    }

    fun cleanName(rawName: String): String {
        return rawName
            .removePrefix("TunnelBear ")
            .removePrefix("TunnelBear_")
            .removePrefix("TunnelBear-")
            .replace("_", " ")
            .trim()
            .ifEmpty { rawName }
    }
}
