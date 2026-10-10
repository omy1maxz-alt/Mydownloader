import re

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

jules_menu_script = """    private fun injectJulesLongPress(webView: WebView?) {
        val script = \"\"\"
            (function() {
                if (window.julesLongPressInjected) return;
                window.julesLongPressInjected = true;

                // Create custom context menu container
                var menu = document.createElement('div');
                menu.id = 'jules-custom-context-menu';
                menu.style.position = 'fixed';
                menu.style.background = '#ffffff';
                menu.style.border = '1px solid #ccc';
                menu.style.borderRadius = '8px';
                menu.style.boxShadow = '0 4px 12px rgba(0,0,0,0.15)';
                menu.style.padding = '8px 0';
                menu.style.zIndex = '999999';
                menu.style.display = 'none';
                menu.style.fontFamily = 'sans-serif';
                menu.style.fontSize = '14px';
                menu.style.minWidth = '160px';

                var copyBtn = document.createElement('div');
                copyBtn.innerText = 'Copy Message';
                copyBtn.style.padding = '12px 16px';
                copyBtn.style.cursor = 'pointer';
                copyBtn.style.color = '#333';
                copyBtn.onmouseover = function() { this.style.backgroundColor = '#f0f0f0'; };
                copyBtn.onmouseout = function() { this.style.backgroundColor = 'transparent'; };

                var editBtn = document.createElement('div');
                editBtn.innerText = 'Copy to Input (Edit)';
                editBtn.style.padding = '12px 16px';
                editBtn.style.cursor = 'pointer';
                editBtn.style.color = '#333';
                editBtn.onmouseover = function() { this.style.backgroundColor = '#f0f0f0'; };
                editBtn.onmouseout = function() { this.style.backgroundColor = 'transparent'; };

                menu.appendChild(copyBtn);
                menu.appendChild(editBtn);
                document.body.appendChild(menu);

                var activeMessageText = "";

                document.addEventListener('click', function() {
                    menu.style.display = 'none';
                });

                document.addEventListener('contextmenu', function(e) {
                    var target = e.target;
                    if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return;
                    if (target.closest('a')) return;

                    var container = target;
                    var bestContainer = null;

                    while (container && container !== document.body) {
                        var role = container.getAttribute('data-message-author-role');
                        if (role === 'model' || role === 'user') {
                            bestContainer = container;
                            break;
                        }
                        if (container.classList.contains('model-turn') || container.classList.contains('user-turn') || container.classList.contains('assistant-message')) {
                            bestContainer = container;
                            break;
                        }
                        if (container.parentElement) {
                            var parentRole = container.parentElement.getAttribute('role');
                            if (parentRole === 'log' || parentRole === 'feed' || parentRole === 'list') {
                                bestContainer = container;
                                break;
                            }
                        }
                        container = container.parentElement;
                    }

                    if (!bestContainer) {
                        container = target;
                        while (container && container !== document.body) {
                            if (container.tagName === 'MAIN') break;
                            if (container.offsetWidth > (window.innerWidth * 0.8)) {
                                bestContainer = container;
                            }
                            container = container.parentElement;
                        }
                    }

                    var finalTarget = bestContainer || target;
                    var text = finalTarget.innerText;

                    if (text && text.trim().length > 0) {
                        e.preventDefault();
                        e.stopPropagation();

                        activeMessageText = text.trim();

                        // Position menu
                        menu.style.left = Math.min(e.clientX, window.innerWidth - 180) + 'px';
                        menu.style.top = Math.min(e.clientY, window.innerHeight - 100) + 'px';
                        menu.style.display = 'block';

                        finalTarget.style.outline = '2px solid #4CAF50';
                        setTimeout(function() { finalTarget.style.outline = ''; }, 200);
                    }
                }, true);

                copyBtn.onclick = function() {
                    if (window.AndroidWebAPI && window.AndroidWebAPI.copyToClipboard) {
                        window.AndroidWebAPI.copyToClipboard(activeMessageText);
                        menu.style.display = 'none';
                    }
                };

                editBtn.onclick = function() {
                    // Try to find the Jules input box. Usually it's a textarea or contenteditable div.
                    var inputArea = document.querySelector('textarea, [contenteditable="true"]');
                    if (inputArea) {
                        if (inputArea.tagName === 'TEXTAREA' || inputArea.tagName === 'INPUT') {
                            inputArea.value = activeMessageText;
                            // Trigger input event to resize textarea and enable send button
                            inputArea.dispatchEvent(new Event('input', { bubbles: true }));
                            inputArea.dispatchEvent(new Event('change', { bubbles: true }));
                        } else {
                            inputArea.innerText = activeMessageText;
                            inputArea.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                        inputArea.focus();
                    } else {
                        // Fallback: Copy to clipboard if input not found
                        if (window.AndroidWebAPI && window.AndroidWebAPI.copyToClipboard) {
                            window.AndroidWebAPI.copyToClipboard(activeMessageText);
                        }
                    }
                    menu.style.display = 'none';
                };
            })();
        \"\"\".trimIndent()
        webView?.evaluateJavascript(script, null)
    }"""

# Using regex to replace the function `injectJulesLongPress`
pattern = r"    private fun injectJulesLongPress\(webView: WebView\?\) \{.*?(?=    private fun injectAdvancedMediaDetector)"
content = re.sub(pattern, jules_menu_script + "\n", content, flags=re.DOTALL)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)
