package com.example.data.model

data class CountryCodeOption(
    val isoCode: String,
    val name: String,
    val dialCode: String,
    val flagEmoji: String
)

object CountryCodes {
    val defaultCountry = CountryCodeOption("ET", "Ethiopia", "+251", "\uD83C\uDDEA\uD83C\uDDF9")

    val all: List<CountryCodeOption> = listOf(
        defaultCountry,
        CountryCodeOption("US", "United States", "+1", "\uD83C\uDDFA\uD83C\uDDF8"),
        CountryCodeOption("GB", "United Kingdom", "+44", "\uD83C\uDDEC\uD83C\uDDE7"),
        CountryCodeOption("AE", "United Arab Emirates", "+971", "\uD83C\uDDE6\uD83C\uDDEA"),
        CountryCodeOption("SA", "Saudi Arabia", "+966", "\uD83C\uDDF8\uD83C\uDDE6"),
        CountryCodeOption("KE", "Kenya", "+254", "\uD83C\uDDF0\uD83C\uDDEA"),
        CountryCodeOption("ZA", "South Africa", "+27", "\uD83C\uDDFF\uD83C\uDDE6"),
        CountryCodeOption("NG", "Nigeria", "+234", "\uD83C\uDDF3\uD83C\uDDEC"),
        CountryCodeOption("EG", "Egypt", "+20", "\uD83C\uDDEA\uD83C\uDDEC"),
        CountryCodeOption("DE", "Germany", "+49", "\uD83C\uDDE9\uD83C\uDDEA"),
        CountryCodeOption("FR", "France", "+33", "\uD83C\uDDEB\uD83C\uDDF7"),
        CountryCodeOption("CA", "Canada", "+1", "\uD83C\uDDE8\uD83C\uDDE6"),
        CountryCodeOption("IN", "India", "+91", "\uD83C\uDDEE\uD83C\uDDF3"),
        CountryCodeOption("CN", "China", "+86", "\uD83C\uDDE8\uD83C\uDDF3"),
        CountryCodeOption("JP", "Japan", "+81", "\uD83C\uDDEF\uD83C\uDDF5"),
        CountryCodeOption("AU", "Australia", "+61", "\uD83C\uDDE6\uD83C\uDDFA"),
        CountryCodeOption("BR", "Brazil", "+55", "\uD83C\uDDE7\uD83C\uDDF7"),
        CountryCodeOption("TR", "Turkey", "+90", "\uD83C\uDDF9\uD83C\uDDF7")
    )

    fun findByDialCode(dialCode: String): CountryCodeOption {
        return all.firstOrNull { it.dialCode == dialCode } ?: defaultCountry
    }

    fun sanitizeLocalDigits(input: String): String {
        val digits = input.filter { it.isDigit() }
        // Strip leading 0 if user enters local format like 0983156394 with +251
        return if (digits.startsWith("0") && digits.length > 6) digits.drop(1) else digits
    }

    fun formatE164(dialCode: String, localPhone: String): String {
        val cleanDial = if (dialCode.startsWith("+")) dialCode else "+$dialCode"
        val cleanLocal = sanitizeLocalDigits(localPhone)
        return "$cleanDial$cleanLocal"
    }

    fun toPhoneClaimKey(e164Phone: String): String {
        return e164Phone.filter { it.isDigit() }
    }

    fun sanitizeUsername(input: String): String {
        return input.trim().removePrefix("@").lowercase()
    }
}
