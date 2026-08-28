fun main() {
    val whitelist = setOf("google.com", "accounts.google.com")
    val url = "https://accounts.google.com/login"
    val host = java.net.URI(url).host

    val matched = whitelist.any { whitelistedDomain -> host == whitelistedDomain || host.endsWith(".$whitelistedDomain") }
    println("matched: $matched")
}
