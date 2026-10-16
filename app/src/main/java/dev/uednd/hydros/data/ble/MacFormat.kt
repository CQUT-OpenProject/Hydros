package dev.uednd.hydros.data.ble


object MacFormat {


    fun normalize(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12) return null
        if (!hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        return hex.uppercase()
    }


    fun lookupVariants(mac: String): List<String> {
        val canon = normalize(mac) ?: return listOf(mac)
        val out = mutableListOf(mac)
        publicAddressOf(canon)?.let { derived ->

            if (derived.lowercase() != mac) out.add(derived.lowercase())
        }
        return out
    }


    fun publicAddressOf(mac: String): String? {
        val canon = normalize(mac) ?: return null
        val first = canon.substring(0, 2).toIntOrNull(16) ?: return null
        if (first and 0xC0 == 0) return null
        return (first and 0x3F).toString(16).uppercase().padStart(2, '0') + canon.substring(2)
    }


    fun connectAddressFor(ledgerMac: String): String {
        val canon = normalize(ledgerMac) ?: return ledgerMac
        val first = canon.substring(0, 2).toIntOrNull(16) ?: return ledgerMac
        if (first and 0xC0 != 0) return canon
        return (first or 0xC0).toString(16).uppercase().padStart(2, '0') + canon.substring(2)
    }
}
