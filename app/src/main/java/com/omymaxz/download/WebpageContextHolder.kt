package com.omymaxz.download

object WebpageContextHolder {
    var url: String? = null
    var title: String? = null
    var textContent: String? = null

    fun clear() {
        url = null
        title = null
        textContent = null
    }
}
