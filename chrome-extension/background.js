const MENU_ID = "ask-nova-selection";

function buildNovaUrl(action, text, url) {
  const parts = ["action=" + encodeURIComponent(action || "ask")];
  if (text) parts.push("text=" + encodeURIComponent(text));
  if (url) parts.push("url=" + encodeURIComponent(url));
  return "nova://assistant?" + parts.join("&");
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_ID,
      title: "Ask Nova about selection",
      contexts: ["selection"]
    });
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId !== MENU_ID) return;
  const text = (info.selectionText || "").trim();
  const pageUrl = (info.pageUrl || (tab && tab.url) || "").trim();
  const deepLink = buildNovaUrl("ask", text, pageUrl);
  chrome.tabs.create({ url: deepLink });
});
