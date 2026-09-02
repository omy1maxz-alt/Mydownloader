import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

# Replace TARGET_LANGUAGE logic in injectTranslateScript
target = """        val translateScript = \"\"\""""
replacement = """
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val targetLang = prefs.getString("translate_target_lang", "en") ?: "en"

        val translateScript = \"\"\""""

content = content.replace(target, replacement)

target2 = "select.value = 'TARGET_LANGUAGE'; // REPLACED LATER IN KOTLIN"
replacement2 = "select.value = '${targetLang}';"

content = content.replace(target2, replacement2)

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
    f.write(content)
