fun main() {
    val url = "https://tmp.cdnvideo11.shop/tmp/Bleach-Thousand-Year-Blood-War-Part-IV--The-Calamity-Ep1/index.m3u8?v=1e99e151-c689-43d1-9583-ab4c8fbe1f87"
    val stripped = url.substringBefore("?")
    println(stripped)
}
