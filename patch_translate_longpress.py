import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

target = """        binding.translateButton.setOnClickListener {
            isAutoTranslateEnabled = !isAutoTranslateEnabled
            if (isAutoTranslateEnabled) {
                binding.translateButton.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                injectTranslateScript(webView)
                Toast.makeText(this, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
            } else {
                binding.translateButton.clearColorFilter()
                Toast.makeText(this, "Auto-Translate OFF (Reloading...)", Toast.LENGTH_SHORT).show()
                webView.reload()
            }
        }"""

replacement = """        binding.translateButton.setOnClickListener {
            isAutoTranslateEnabled = !isAutoTranslateEnabled
            val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("auto_translate_enabled", isAutoTranslateEnabled).apply()

            if (isAutoTranslateEnabled) {
                binding.translateButton.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                injectTranslateScript(webView)
                Toast.makeText(this, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
            } else {
                binding.translateButton.clearColorFilter()
                Toast.makeText(this, "Auto-Translate OFF (Reloading...)", Toast.LENGTH_SHORT).show()
                webView.reload()
            }
        }

        binding.translateButton.setOnLongClickListener {
            showTranslateSettingsDialog()
            true
        }"""

content = content.replace(target, replacement)

# Add showTranslateSettingsDialog function at the end of MainActivity class
target2 = """}"""
# Need to find the last occurrence of '}' to append the function
last_brace_index = content.rfind('}')
if last_brace_index != -1:
    dialog_code = """
    private fun showTranslateSettingsDialog() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("translate_target_lang", "en") ?: "en"
        val isAuto = prefs.getBoolean("auto_translate_enabled", false)

        val languages = arrayOf("English", "Indonesian", "Spanish", "Korean", "Japanese", "Chinese (Simplified)")
        val languageCodes = arrayOf("en", "id", "es", "ko", "ja", "zh-CN")

        var selectedIndex = languageCodes.indexOf(currentLang)
        if (selectedIndex == -1) selectedIndex = 0

        val dialogView = layoutInflater.inflate(R.layout.dialog_translate_settings, null)
        val spinner = dialogView.findViewById<android.widget.Spinner>(R.id.language_spinner)
        val switchAuto = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_auto_translate)

        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selectedIndex)

        switchAuto.isChecked = isAuto

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Translation Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newLangIndex = spinner.selectedItemPosition
                val newLangCode = languageCodes[newLangIndex]
                val newAuto = switchAuto.isChecked

                prefs.edit()
                    .putString("translate_target_lang", newLangCode)
                    .putBoolean("auto_translate_enabled", newAuto)
                    .apply()

                if (newAuto != isAuto) {
                    isAutoTranslateEnabled = newAuto
                    if (isAutoTranslateEnabled) {
                        binding.translateButton.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                        injectTranslateScript(webView)
                        Toast.makeText(this, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.translateButton.clearColorFilter()
                        Toast.makeText(this, "Auto-Translate OFF (Reloading...)", Toast.LENGTH_SHORT).show()
                        webView.reload()
                    }
                } else if (newAuto && newLangCode != currentLang) {
                    // Language changed, re-inject translation with new language
                    Toast.makeText(this, "Language updated", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
"""
    content = content[:last_brace_index] + dialog_code

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
    f.write(content)
