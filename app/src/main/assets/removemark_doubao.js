(function () {
  "use strict";

  const DOCK_VERSION = "2024.11.18";
  const BUTTON_ATTR = "data-removemark-doubao";
  const HOST_ATTR = "data-removemark-doubao-host";

  let lastHref = window.location.href;
  let doubaoImages = [];
  let selectedImageUrls = new Set();
  let dockCollapsed = false;
  let dockBusy = false;
  let scanTimer = 0;

  // From doubao-hook.js
  const imageMap = new Map();

  function emitImages() {
    window.dispatchEvent(
      new CustomEvent("removemark:doubao-images", {
        detail: { images: Array.from(imageMap.values()) },
      })
    );
  }

  function rememberImages(bucket) {
    if (!bucket || !bucket.length) return;
    let mutated = false;
    for (const item of bucket) {
      if (!item?.url) continue;
      if (!imageMap.has(item.url)) {
        imageMap.set(item.url, item);
        mutated = true;
      }
    }
    if (mutated) {
      emitImages();
    }
  }

  function parseImageValue(rawValue) {
    if (typeof rawValue === "string") {
      return {
        url: rawValue.replace(/&amp;/g, "&"),
        width: 0,
        height: 0
      };
    }
    if (typeof rawValue === "object" && rawValue.url) {
      return {
        url: String(rawValue.url).replace(/&amp;/g, "&"),
        width: Number(rawValue.width || 0),
        height: Number(rawValue.height || 0)
      };
    }
    return null;
  }

  function collectImagesFromCreations(creations, bucket) {
    if (!Array.isArray(creations)) return;
    for (const creation of creations) {
      const imageNode = creation?.image;
      const rawImage = parseImageValue(imageNode?.image_ori_raw);
      if (!rawImage) continue;

      const relatedUrls = [
        rawImage.url,
        parseImageValue(imageNode?.image_ori)?.url,
        parseImageValue(imageNode?.image_preview)?.url,
        parseImageValue(imageNode?.image_thumb)?.url
      ].filter(Boolean);

      bucket.push({
        url: rawImage.url,
        width: rawImage.width,
        height: rawImage.height,
        relatedUrls: Array.from(new Set(relatedUrls))
      });
    }
  }

  function collectImagesFromBlocks(blocks, bucket) {
    if (!Array.isArray(blocks)) return;
    for (const block of blocks) {
      const directContent = block?.content;
      collectImagesFromCreations(directContent?.creation_block?.creations, bucket);

      if (typeof block?.content_v2 === "string") {
        try {
          const parsedContent = JSON.parse(block.content_v2);
          collectImagesFromCreations(parsedContent?.creation_block?.creations, bucket);
        } catch {}
      }
    }
  }

  function collectImagesFromMessages(messages, bucket) {
    for (const message of messages || []) {
      collectImagesFromBlocks(message?.content_block, bucket);
    }
  }

  function extractImagesFromMessages(messages) {
    const bucket = [];
    collectImagesFromMessages(messages, bucket);
    return bucket;
  }

  function extractImagesFromCreationContent(rawText) {
    const bucket = [];
    try {
      const parsed = JSON.parse(rawText);
      if (!Array.isArray(parsed)) return bucket;

      for (const item of parsed) {
        collectImagesFromCreations(item?.BlockInfo?.BlockContent?.content?.creation_block?.creations, bucket);
      }
    } catch {}
    return bucket;
  }

  function deepCollectImageOriRaw(node, bucket, seen) {
    if (!node || typeof node !== "object") return;
    if (seen.has(node)) return;
    seen.add(node);

    if (Object.prototype.hasOwnProperty.call(node, "image_ori_raw")) {
      const rawImage = parseImageValue(node.image_ori_raw);
      if (rawImage) {
        const relatedUrls = [
          rawImage.url,
          parseImageValue(node.image_ori)?.url,
          parseImageValue(node.image_preview)?.url,
          parseImageValue(node.image_thumb)?.url
        ].filter(Boolean);

        bucket.push({
          url: rawImage.url,
          width: rawImage.width,
          height: rawImage.height,
          relatedUrls: Array.from(new Set(relatedUrls))
        });
      }
    }

    if (Array.isArray(node)) {
      for (const item of node) {
        deepCollectImageOriRaw(item, bucket, seen);
      }
      return;
    }

    for (const value of Object.values(node)) {
      if (value && typeof value === "object") {
        deepCollectImageOriRaw(value, bucket, seen);
      }
    }
  }

  function collectFromUnknownPayload(payload) {
    const bucket = [];
    deepCollectImageOriRaw(payload, bucket, new WeakSet());
    rememberImages(bucket);
  }

  function inspectRouterNode(node, bucket) {
    if (!node || typeof node !== "object") return;
    collectImagesFromMessages(node?.data?.message_snapshot?.message_list, bucket);
    collectImagesFromMessages(node?.message_snapshot?.message_list, bucket);
    deepCollectImageOriRaw(node, bucket, new WeakSet());
  }

  function parseSnapshotFromDocument() {
    const bucket = [];
    const routerScript = document.querySelector('script[data-script-src="modern-run-router-data-fn"]');

    if (routerScript) {
      const args = routerScript.getAttribute("data-fn-args");
      if (args) {
        try {
          const decoded = JSON.parse(args.replace(/&quot;/g, '"'));
          if (Array.isArray(decoded)) {
            for (const node of decoded) {
              inspectRouterNode(node, bucket);
            }
          }
        } catch {}
      }
    }

    const routerData = window._ROUTER_DATA?.loaderData;
    if (routerData && typeof routerData === "object") {
      for (const node of Object.values(routerData)) {
        inspectRouterNode(node, bucket);
      }
    }

    rememberImages(bucket);
  }

  function parsePatchPayload(payload) {
    const bucket = [];
    for (const patch of payload || []) {
      collectImagesFromBlocks(patch?.patch_value?.content_block, bucket);
      const fullContent = patch?.patch_value?.ext?.creation_full_content;
      if (typeof fullContent === "string") {
        bucket.push(...extractImagesFromCreationContent(fullContent));
      }
    }
    deepCollectImageOriRaw(payload, bucket, new WeakSet());
    rememberImages(bucket);
  }

  function parseEventDataString(rawEventData) {
    try {
      const parsedEvent = JSON.parse(rawEventData);
      const messageContent = JSON.parse(parsedEvent?.message?.content || "{}");
      const bucket = [];
      collectImagesFromCreations(messageContent?.creations, bucket);
      deepCollectImageOriRaw(parsedEvent, bucket, new WeakSet());
      rememberImages(bucket);
    } catch {}
  }

  function parseChatPayload(payload) {
    const messages = payload?.downlink_body?.pull_singe_chain_downlink_body?.messages;
    if (Array.isArray(messages)) {
      rememberImages(extractImagesFromMessages(messages));
    }
    collectFromUnknownPayload(payload);
  }

  function parseEventStream(text) {
    const lines = text.split("\n");
    for (const line of lines) {
      if (!line.startsWith("data: ")) continue;
      try {
        const payload = JSON.parse(line.slice(6));
        if (Array.isArray(payload?.patch_op)) {
          parsePatchPayload(payload.patch_op);
        } else if (typeof payload?.event_data === "string") {
          parseEventDataString(payload.event_data);
        } else {
          collectFromUnknownPayload(payload);
        }
      } catch {}
    }
  }

  function shouldInspectRequestUrl(url) {
    const currentUrl = String(url || "");
    if (!currentUrl) return false;
    return (
      currentUrl.includes("/im/chain/single") ||
      currentUrl.includes("/chat/completion") ||
      currentUrl.includes("/samantha/") ||
      currentUrl.includes("/alice/") ||
      currentUrl.includes("image_ori_raw")
    );
  }

  function installXHRHook() {
    if (window._removeMarkXhrHookInstalled) return;
    window._removeMarkXhrHookInstalled = true;

    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function patchedOpen(method, url, ...args) {
      this.__removeMarkUrl = url;
      return originalOpen.call(this, method, url, ...args);
    };

    XMLHttpRequest.prototype.send = function patchedSend(...args) {
      this.addEventListener("load", function onLoad() {
        const currentUrl = String(this.__removeMarkUrl || "");
        if (!shouldInspectRequestUrl(currentUrl) && !String(this.responseText || "").includes("image_ori_raw")) {
          return;
        }

        try {
          if (currentUrl.includes("/im/chain/single")) {
            parseChatPayload(JSON.parse(this.responseText));
            return;
          }
          collectFromUnknownPayload(JSON.parse(this.responseText));
        } catch {}
      });

      return originalSend.apply(this, args);
    };
  }

  function installFetchHook() {
    if (window._removeMarkFetchHookInstalled) return;
    window._removeMarkFetchHookInstalled = true;

    const originalFetch = window.fetch;

    window.fetch = async function patchedFetch(...args) {
      const response = await originalFetch.apply(this, args);
      const currentUrl = String(args[0]?.url || args[0] || "");

      if (currentUrl.includes("/chat/completion") || shouldInspectRequestUrl(currentUrl)) {
        const contentType = String(response.headers.get("content-type") || "");

        if (contentType.includes("text/event-stream") || currentUrl.includes("/chat/completion")) {
          response
            .clone()
            .text()
            .then(parseEventStream)
            .catch(() => {});
        } else {
          response
            .clone()
            .text()
            .then((text) => {
              if (!text.includes("image_ori_raw")) return;
              try {
                collectFromUnknownPayload(JSON.parse(text));
              } catch {
                parseEventStream(text);
              }
            })
            .catch(() => {});
        }
      }

      return response;
    };
  }

  function resetOnRouteChange() {
    if (window.location.href === lastHref) return;
    lastHref = window.location.href;
    imageMap.clear();
    emitImages();
    setTimeout(parseSnapshotFromDocument, 250);
  }

  // Styles
  function ensureStyles() {
    if (document.getElementById("removemark-doubao-style")) return;

    const style = document.createElement("style");
    style.id = "removemark-doubao-style";
    style.textContent = `
      #removemark-doubao-dock {
        position: fixed;
        bottom: 24px;
        right: 24px;
        z-index: 999999;
        width: 340px;
        background: #fff;
        border-radius: 16px;
        box-shadow: 0 8px 32px rgba(0,0,0,0.12), 0 2px 8px rgba(0,0,0,0.08);
        font-family: system-ui, -apple-system, sans-serif;
        color: #1a1a1a;
        overflow: hidden;
        transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        display: none;
      }

      #removemark-doubao-dock.is-open {
        display: block;
      }

      #removemark-doubao-dock.is-collapsed .rm-dock-body {
        display: none;
      }

      .rm-dock-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 12px 16px;
        background: #f8f9fa;
        border-bottom: 1px solid #e9ecef;
      }

      .rm-dock-brand {
        display: flex;
        align-items: center;
        gap: 12px;
      }

      .rm-dock-logo {
        width: 32px;
        height: 32px;
        border-radius: 8px;
      }

      .rm-dock-copy {
        display: flex;
        flex-direction: column;
        line-height: 1.4;
      }

      .rm-dock-copy strong {
        font-size: 14px;
        font-weight: 600;
      }

      .rm-dock-copy span {
        font-size: 12px;
        color: #6c757d;
      }

      .rm-dock-toggle {
        background: none;
        border: none;
        font-size: 24px;
        color: #495057;
        cursor: pointer;
        padding: 0 8px;
        line-height: 1;
      }

      .rm-dock-body {
        padding: 16px;
      }

      .rm-dock-tools {
        display: flex;
        gap: 8px;
        margin-bottom: 12px;
      }

      .rm-dock-tools button {
        background: #f1f3f5;
        border: none;
        padding: 6px 12px;
        border-radius: 6px;
        font-size: 12px;
        cursor: pointer;
        color: #495057;
        font-weight: 500;
      }

      .rm-dock-tools button:hover {
        background: #e9ecef;
      }

      .rm-dock-list {
        max-height: 300px;
        overflow-y: auto;
        margin-bottom: 16px;
        border: 1px solid #e9ecef;
        border-radius: 8px;
      }

      .rm-dock-item {
        display: flex;
        align-items: center;
        padding: 12px;
        border-bottom: 1px solid #e9ecef;
        cursor: pointer;
        transition: background 0.2s;
      }

      .rm-dock-item:last-child {
        border-bottom: none;
      }

      .rm-dock-item:hover {
        background: #f8f9fa;
      }

      .rm-dock-item.is-checked {
        background: #e6fcf5;
      }

      .rm-dock-item input[type="checkbox"] {
        margin-right: 12px;
        width: 16px;
        height: 16px;
        cursor: pointer;
      }

      .rm-dock-item img {
        width: 48px;
        height: 48px;
        object-fit: cover;
        border-radius: 6px;
        margin-right: 12px;
        background: #e9ecef;
      }

      .rm-dock-meta {
        display: flex;
        flex-direction: column;
        flex: 1;
      }

      .rm-dock-meta strong {
        font-size: 13px;
        margin-bottom: 4px;
      }

      .rm-dock-meta span {
        font-size: 11px;
        color: #6c757d;
      }

      .rm-dock-actions {
        display: flex;
      }

      .rm-dock-actions button {
        width: 100%;
        background: #20c997;
        color: white;
        border: none;
        padding: 12px;
        border-radius: 8px;
        font-size: 14px;
        font-weight: 600;
        cursor: pointer;
        transition: background 0.2s;
      }

      .rm-dock-actions button:hover {
        background: #12b886;
      }

      .rm-dock-actions button:disabled {
        background: #ced4da;
        cursor: not-allowed;
      }

      .rm-dock-footnote {
        font-size: 11px;
        color: #adb5bd;
        text-align: center;
        margin-top: 12px;
      }
    `;
    document.head.appendChild(style);
  }

  // App Logic
  function dedupeImages(images) {
    const seen = new Set();
    return images.filter((item) => {
      if (!item?.url) return false;
      if (seen.has(item.url)) return false;
      seen.add(item.url);
      return true;
    });
  }

  function pruneSelection() {
    const validUrls = new Set(doubaoImages.map((item) => item.url));
    for (const url of selectedImageUrls) {
      if (!validUrls.has(url)) {
        selectedImageUrls.delete(url);
      }
    }
  }

  function setButtonState(button, stateClass, labelText) {
    if (!button) return;
    button.className = `rm-primary ${stateClass}`;
    const labelNode = button.querySelector('[data-role="label"]');
    if (labelNode) {
      labelNode.textContent = labelText;
    } else {
      button.textContent = labelText;
    }
  }

  function downloadSelectedImages(triggerButton) {
    const selected = doubaoImages.filter((item) => selectedImageUrls.has(item.url));
    if (!selected.length) return;

    dockBusy = true;
    const originalLabel = triggerButton.querySelector('[data-role="label"]')?.textContent || triggerButton.textContent;
    triggerButton.disabled = true;
    setButtonState(triggerButton, "is-busy", `Downloading 0/${selected.length}`);

    let successCount = 0;

    // Instead of doing JS fetch + ObjectURL downloads like Chrome Extension,
    // we bridge it directly to Android's DownloadManager.
    for (let index = 0; index < selected.length; index += 1) {
      setButtonState(triggerButton, "is-busy", `Downloading ${index + 1}/${selected.length}`);
      const item = selected[index];

      if (window.AndroidDoubao && window.AndroidDoubao.downloadImage) {
          window.AndroidDoubao.downloadImage(item.url);
          successCount += 1;
      }
    }

    setButtonState(triggerButton, successCount ? "is-done" : "is-error", successCount ? `Started ${successCount}` : "Failed");

    window.setTimeout(() => {
      triggerButton.disabled = false;
      setButtonState(triggerButton, "", originalLabel);
      dockBusy = false;
      syncFallbackDock();
    }, 1400);
  }

  function ensureFallbackDock() {
    let dock = document.getElementById("removemark-doubao-dock");
    if (dock && dock.dataset.rmVersion !== DOCK_VERSION) {
      dock.remove();
      dock = null;
    }

    if (dock) return dock;

    dock = document.createElement("div");
    dock.id = "removemark-doubao-dock";
    dock.dataset.rmVersion = DOCK_VERSION;

    // We use a simple data URI for a generic "image download" SVG icon instead of Chrome getURL.
    const logoSvg = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%2320c997'><path d='M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z'/></svg>";

    dock.innerHTML = `
      <div class="rm-dock-head">
        <div class="rm-dock-brand">
          <img class="rm-dock-logo" src="${logoSvg}" alt="" />
          <div class="rm-dock-copy">
            <strong>Original Images</strong>
            <span data-role="count">Waiting for images...</span>
          </div>
        </div>
        <button type="button" class="rm-dock-toggle" data-role="toggle" aria-label="Collapse">–</button>
      </div>
      <div class="rm-dock-body">
        <div class="rm-dock-tools">
          <button type="button" data-role="select-all">Select All</button>
          <button type="button" data-role="select-none">Deselect All</button>
        </div>
        <div class="rm-dock-list" data-role="list"></div>
        <div class="rm-dock-actions">
          <button type="button" class="rm-primary" data-role="download-selected">
            <span data-role="label">Download Selected</span>
          </button>
        </div>
        <div class="rm-dock-footnote">Download original un-watermarked images without affecting Doubao page layout</div>
      </div>
    `;

    dock.querySelector('[data-role="toggle"]').addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      dockCollapsed = !dockCollapsed;
      dock.classList.toggle("is-collapsed", dockCollapsed);
      event.currentTarget.textContent = dockCollapsed ? "+" : "–";
    });

    dock.querySelector('[data-role="select-all"]').addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      selectedImageUrls = new Set(doubaoImages.map((item) => item.url));
      syncFallbackDock();
    });

    dock.querySelector('[data-role="select-none"]').addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      selectedImageUrls.clear();
      syncFallbackDock();
    });

    dock.querySelector('[data-role="download-selected"]').addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      downloadSelectedImages(event.currentTarget);
    });

    document.documentElement.appendChild(dock);
    return dock;
  }

  function previewUrlForItem(item) {
    const related = Array.isArray(item.relatedUrls) ? item.relatedUrls : [];
    const httpUrls = related.filter((url) => typeof url === "string" && url.startsWith("http"));
    const preferPreview = httpUrls.find((url) => /thumb|preview|downsize|pre_watermark|dld_watermark/i.test(url));
    const avoidRaw = httpUrls.find((url) => !/image_raw/i.test(url));
    return preferPreview || avoidRaw || item.url;
  }

  function syncFallbackDock(matchedCount = 0) {
    const dock = ensureFallbackDock();
    const countNode = dock.querySelector('[data-role="count"]');
    const listNode = dock.querySelector('[data-role="list"]');
    const downloadSelectedButton = dock.querySelector('[data-role="download-selected"]');
    const toggleButton = dock.querySelector('[data-role="toggle"]');

    dock.classList.toggle("is-collapsed", dockCollapsed);
    toggleButton.textContent = dockCollapsed ? "+" : "–";

    if (!doubaoImages.length) {
      dock.classList.remove("is-open");
      listNode.replaceChildren();
      return;
    }

    pruneSelection();
    const selectedCount = selectedImageUrls.size;

    countNode.textContent = matchedCount
      ? `Found ${doubaoImages.length} · Selected ${selectedCount}`
      : `Found ${doubaoImages.length} raw images · Selected ${selectedCount}`;

    listNode.replaceChildren();
    doubaoImages.forEach((item, index) => {
      const checked = selectedImageUrls.has(item.url);
      const row = document.createElement("label");
      row.className = `rm-dock-item${checked ? " is-checked" : ""}`;

      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = checked;
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) {
          selectedImageUrls.add(item.url);
        } else {
          selectedImageUrls.delete(item.url);
        }
        syncFallbackDock(matchedCount);
      });

      const thumb = document.createElement("img");
      thumb.alt = `Raw ${index + 1}`;
      thumb.loading = "lazy";
      thumb.referrerPolicy = "no-referrer";
      thumb.src = previewUrlForItem(item);

      const meta = document.createElement("div");
      meta.className = "rm-dock-meta";
      const title = document.createElement("strong");
      title.textContent = `Raw Image ${String(index + 1).padStart(2, "0")}`;
      const subtitle = document.createElement("span");
      subtitle.textContent = item.width && item.height ? `${item.width} × ${item.height}` : "Ready for download";
      meta.append(title, subtitle);

      row.append(checkbox, thumb, meta);
      listNode.appendChild(row);
    });

    if (!dockBusy) {
      downloadSelectedButton.disabled = selectedCount === 0;
      setButtonState(
        downloadSelectedButton,
        "",
        selectedCount ? `Download Selected (${selectedCount})` : "Download Selected"
      );
    }

    dock.classList.add("is-open");
  }

  function syncPageButtons() {
    ensureStyles();
    syncFallbackDock(0);
  }

  function scheduleSyncPageButtons() {
    window.clearTimeout(scanTimer);
    scanTimer = window.setTimeout(syncPageButtons, 160);
  }

  window.addEventListener("removemark:doubao-images", (event) => {
    const previousUrls = new Set(doubaoImages.map((item) => item.url));
    const nextImages = Array.isArray(event.detail?.images) ? event.detail.images : [];
    doubaoImages = dedupeImages(nextImages);

    for (const item of doubaoImages) {
      if (!previousUrls.has(item.url)) {
        selectedImageUrls.add(item.url);
      }
    }

    if (!previousUrls.size && doubaoImages.length && !selectedImageUrls.size) {
      selectedImageUrls = new Set(doubaoImages.map((item) => item.url));
    }

    pruneSelection();
    scheduleSyncPageButtons();
  });

  // Initialization
  installXHRHook();
  installFetchHook();

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => {
      parseSnapshotFromDocument();
      scheduleSyncPageButtons();
    }, { once: true });
  } else {
    parseSnapshotFromDocument();
    scheduleSyncPageButtons();
  }

  setTimeout(parseSnapshotFromDocument, 800);
  setInterval(resetOnRouteChange, 1000);
  window.setInterval(() => {
    parseSnapshotFromDocument();
    scheduleSyncPageButtons();
  }, 4000);
})();
