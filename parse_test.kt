import java.util.Base64

fun main() {
    var encoded = "WZnaHR0cHM6Ly9hYnlzc3BsYXllci5jb20vclU0dXpZby1VeA=="
    if (encoded.startsWith("WZ")) {
        encoded = encoded.substring(2)
    }
    encoded = encoded.trim()
    val padding = (4 - encoded.length % 4) % 4
    encoded += "=".repeat(padding)
    try {
        val decoded = String(Base64.getDecoder().decode(encoded))
        println(decoded)
    } catch (e: Exception) {
        println(e)
    }
}
