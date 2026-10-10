// Jules Research Script
// From what we know, "Jules" at jules.google.com does not natively support "Editing" an old message server-side in a way that diverges a single chat thread into multiple branches visually unless they recently added it to the web UI.
// However, the standard UX for "Edit" in many chat UIs is copying the old text into the input box so the user can modify it and send a NEW message (or a branch).
// Let's implement an "Edit" and "Copy" custom context menu that injects locally. We already have the "copyToClipboard" logic.

// Let's update injectJulesLongPress to inject a custom context menu
