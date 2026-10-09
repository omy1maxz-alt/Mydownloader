with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

import re

# Add DoubaoInterface blob methods
blob_methods = """    inner class DoubaoInterface(private val activity: MainActivity) {
        private val blobSessions = mutableMapOf<String, java.io.FileOutputStream>()

        @JavascriptInterface
        fun startBlobDownload(url: String, mimeType: String, totalSize: Int): String {
            val sessionId = java.util.UUID.randomUUID().toString()
            try {
                val extension = if (mimeType.contains("png")) "png" else if (mimeType.contains("webp")) "webp" else "jpg"
                val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Doubao_${System.currentTimeMillis()}.$extension")
                val fos = java.io.FileOutputStream(file)
                blobSessions[sessionId] = fos
                return sessionId
            } catch (e: Exception) {
                e.printStackTrace()
                return ""
            }
        }

        @JavascriptInterface
        fun appendBlobChunk(sessionId: String, base64Chunk: String) {
            try {
                val fos = blobSessions[sessionId] ?: return
                // Remove potential data URI prefix if accidentally passed
                val cleanBase64 = if (base64Chunk.contains(",")) base64Chunk.substringAfter(",") else base64Chunk
                val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                fos.write(bytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun finishBlobDownload(sessionId: String) {
            try {
                blobSessions[sessionId]?.apply {
                    flush()
                    close()
                }
                blobSessions.remove(sessionId)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Blob Download complete", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    Toast.makeText(activity, "Blob Download failed to finish", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun downloadImage(url: String) {"""

content = content.replace("""    inner class DoubaoInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun downloadImage(url: String) {""", blob_methods)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

with open("app/src/main/assets/removemark_doubao.js", "r") as f:
    js_content = f.read()

# Update downloadSelectedImages in JS
js_download = """  async function processBlobDownload(url, button) {
    try {
      const res = await fetch(url);
      const blob = await res.blob();
      const mimeType = blob.type || "image/jpeg";

      const sessionId = window.AndroidDoubao.startBlobDownload(url, mimeType, blob.size);
      if (!sessionId) {
         console.error("[Doubao] Failed to start blob session.");
         return false;
      }

      const buffer = await blob.arrayBuffer();
      const chunkSize = 512 * 1024; // 512KB chunks
      const uint8Array = new Uint8Array(buffer);

      for (let i = 0; i < uint8Array.length; i += chunkSize) {
        const chunk = uint8Array.slice(i, i + chunkSize);
        // Convert to base64
        let binary = '';
        for (let j = 0; j < chunk.byteLength; j++) {
            binary += String.fromCharCode(chunk[j]);
        }
        const base64 = btoa(binary);
        window.AndroidDoubao.appendBlobChunk(sessionId, base64);
      }

      window.AndroidDoubao.finishBlobDownload(sessionId);
      return true;
    } catch(e) {
      console.error("[Doubao] Blob download error:", e);
      return false;
    }
  }

  async function downloadSelectedImages(triggerButton) {
    const selected = doubaoImages.filter((item) => selectedImageUrls.has(item.url));
    if (!selected.length) return;

    dockBusy = true;
    const originalLabel = triggerButton.querySelector('[data-role="label"]')?.textContent || triggerButton.textContent;
    triggerButton.disabled = true;
    setButtonState(triggerButton, "is-busy", `Downloading 0/${selected.length}`);

    let successCount = 0;

    for (let index = 0; index < selected.length; index += 1) {
      setButtonState(triggerButton, "is-busy", `Downloading ${index + 1}/${selected.length}`);
      const item = selected[index];

      if (window.AndroidDoubao) {
          if (item.url.startsWith("blob:")) {
              if (window.AndroidDoubao.startBlobDownload) {
                  const success = await processBlobDownload(item.url, triggerButton);
                  if (success) successCount += 1;
              }
          } else {
              if (window.AndroidDoubao.downloadImage) {
                  window.AndroidDoubao.downloadImage(item.url);
                  successCount += 1;
              }
          }
      }
    }

    setButtonState(triggerButton, successCount ? "is-done" : "is-error", successCount ? `Started ${successCount}` : "Failed");

    window.setTimeout(() => {
      triggerButton.disabled = false;
      setButtonState(triggerButton, "", originalLabel);
      dockBusy = false;
      syncFallbackDock();
    }, 1400);
  }"""

js_content = re.sub(r'function downloadSelectedImages\(triggerButton\)\s*\{.*?(?=function ensureFallbackDock)', js_download + "\n\n  ", js_content, flags=re.DOTALL)

with open("app/src/main/assets/removemark_doubao.js", "w") as f:
    f.write(js_content)
