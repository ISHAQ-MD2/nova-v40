function buildNovaUrl(action, text, url) {
  const parts = ["action=" + encodeURIComponent(action || "ask")];
  if (text) parts.push("text=" + encodeURIComponent(text));
  if (url) parts.push("url=" + encodeURIComponent(url));
  return "nova://assistant?" + parts.join("&");
}

function setStatus(msg) {
  const el = document.getElementById("status");
  if (el) el.textContent = msg || "";
}

async function getActiveTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  return tabs && tabs.length ? tabs[0] : null;
}

document.getElementById("send-page").addEventListener("click", async () => {
  const tab = await getActiveTab();
  if (!tab || !tab.url) {
    setStatus("No active tab URL found.");
    return;
  }
  const title = (tab.title || "").trim();
  const deepLink = buildNovaUrl("ask", title, tab.url);
  chrome.tabs.create({ url: deepLink });
});

document.getElementById("ask-selection").addEventListener("click", async () => {
  const tab = await getActiveTab();
  if (!tab || !tab.id) {
    setStatus("No active tab found.");
    return;
  }
  let selectedText = "";
  try {
    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => (window.getSelection ? String(window.getSelection()).trim() : "")
    });
    if (results && results[0] && typeof results[0].result === "string") {
      selectedText = results[0].result.trim();
    }
  } catch (err) {
    setStatus("Cannot read selection on this page.");
    return;
  }
  if (!selectedText) {
    setStatus("Select some text on the page first.");
    return;
  }
  const deepLink = buildNovaUrl("ask", selectedText, tab.url || "");
  chrome.tabs.create({ url: deepLink });
});
