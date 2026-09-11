import sys

filename = "app/src/main/java/com/omymaxz/download/MainActivity.kt"
with open(filename, "r") as f:
    content = f.read()

# Fix Conflicting declarations: val referer: String?, val referer: String?
content = content.replace("val referer = reqHeaders?.get(\"Referer\") ?: reqHeaders?.get(\"referer\")", "val reqReferer = reqHeaders?.get(\"Referer\") ?: reqHeaders?.get(\"referer\")")
content = content.replace("mediaEngine.processRequest(url, referer, userAgent)", "mediaEngine.processRequest(url, reqReferer, userAgent)")

with open(filename, "w") as f:
    f.write(content)
