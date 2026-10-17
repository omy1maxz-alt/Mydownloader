# GoPlay Extension CRX — Android port notes

Source inspected: `goplay.crx`, GoPlay Extension version 1.0.2 (Manifest V3).

## What the extension contains

The CRX is a declarative network-request ruleset, not a popup or content-script extension. It has four rules:
- VIU XHR URL signatures: set Referer/Origin to VIU.
- Viki XHR/image URL signatures: set Referer/Origin to Viki.
- GagaOOLala DASH media URL signatures: set Referer/Origin to GagaOOLala.
- GoPlay and armsasuncion URLs: add `X-Gp-Chrome-Ext: 1.02`.

It also adds `Access-Control-Allow-Origin: *` to responses matched by rules 1–3.

## Android status

`GoPlayRequestRules.kt` records the original URL signature matching and request-header overrides, and has host/signature tests. `MainActivity` logs when a rule signature is matched while processing WebView requests.

**Important platform limitation:** Android WebView's `WebViewClient.shouldInterceptRequest` does not let an app mutate the headers of the original request, and it does not provide the original response for simple header rewriting. Returning a synthetic `WebResourceResponse` would replace the content rather than modify headers. Therefore this integration does not yet apply the overrides to live traffic. To achieve parity, implement a carefully scoped request bridge that performs the matching requests with `HttpURLConnection`/OkHttp and streams response bodies with status and MIME metadata, or use a local proxy; ensure redirects, cookies, range requests, streaming manifests, and errors are preserved. For CORS-only use cases, prefer a narrowly scoped solution rather than global wildcard response headers.

The CRX host permissions were recorded in `GoPlayRequestRules.isSupportedHost`; Android app network access itself is governed by the app manifest and WebView.

## Validation

Unit tests were added for the rule signatures and header values. Build and real-device playback validation should be performed in Android Studio/CI before treating the port as complete.
