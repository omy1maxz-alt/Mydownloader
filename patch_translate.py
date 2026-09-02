import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

# Replace the injectTranslateScript string logic to include CSS hiding the UI
old_script = """
            (function() {
                if (document.getElementById('google_translate_element')) return;

                var translateDiv = document.createElement('div');
                translateDiv.id = 'google_translate_element';
                translateDiv.style.position = 'fixed';
                translateDiv.style.bottom = '10px';
                translateDiv.style.right = '10px';
                translateDiv.style.zIndex = '999999';
                translateDiv.style.backgroundColor = 'white';
                translateDiv.style.padding = '5px';
                translateDiv.style.border = '1px solid #ccc';
                translateDiv.style.borderRadius = '5px';
                document.body.appendChild(translateDiv);

                window.googleTranslateElementInit = function() {
                    new google.translate.TranslateElement({
                        pageLanguage: 'auto',
                        layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                        autoDisplay: true
                    }, 'google_translate_element');

                    // Auto-trigger translation to English
                    setTimeout(function() {
                        var select = document.querySelector('.goog-te-combo');
                        if (select) {
                            select.value = 'en';
                            select.dispatchEvent(new Event('change'));
                        }
"""

# Modify injectTranslateScript
# Include a CSS style to hide the google translate element and banner
new_script = """
            (function() {
                // Add CSS to hide the Translate UI and banner
                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = `
                    #google_translate_element, .skiptranslate, .goog-te-banner-frame {
                        display: none !important;
                    }
                    body {
                        top: 0 !important;
                    }
                `;
                document.head.appendChild(style);

                if (document.getElementById('google_translate_element')) return;

                var translateDiv = document.createElement('div');
                translateDiv.id = 'google_translate_element';
                // Hidden via CSS above
                document.body.appendChild(translateDiv);

                window.googleTranslateElementInit = function() {
                    new google.translate.TranslateElement({
                        pageLanguage: 'auto',
                        layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                        autoDisplay: true
                    }, 'google_translate_element');

                    // Auto-trigger translation
                    setTimeout(function() {
                        var select = document.querySelector('.goog-te-combo');
                        if (select) {
                            select.value = 'TARGET_LANGUAGE'; // REPLACED LATER IN KOTLIN
                            select.dispatchEvent(new Event('change'));
                        }
"""

content = content.replace(old_script, new_script)

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'w') as f:
    f.write(content)
