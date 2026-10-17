let pendingSearch = Promise.resolve();

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type !== "markusaller-search") {
    return false;
  }

  pendingSearch = pendingSearch
    .catch((error) => {
      console.error("Previous Markusaller search failed", error);
    })
    .then(() => openSearchInManagedTab(message.target, sender.tab?.id));
  pendingSearch.then(
    () => sendResponse({ ok: true }),
    (error) => {
      console.error("Markusaller search could not be opened", error);
      sendResponse({ ok: false });
    }
  );
  return true;
});

async function openSearchInManagedTab(target, sourceTabId) {
  if (!isAllowedSearch(target) || sourceTabId === undefined) {
    throw new Error("Invalid Markusaller search request");
  }

  const { managedSearchTabId } = await chrome.storage.local.get("managedSearchTabId");
  if (managedSearchTabId !== undefined && managedSearchTabId !== sourceTabId) {
    try {
      await chrome.tabs.update(managedSearchTabId, { url: target, active: true });
      await chrome.tabs.remove(sourceTabId);
      return;
    } catch {
      await chrome.storage.local.remove("managedSearchTabId");
    }
  }

  await chrome.tabs.update(sourceTabId, { url: target, active: true });
  await chrome.storage.local.set({ managedSearchTabId: sourceTabId });
}

function isAllowedSearch(value) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" &&
      url.hostname === "www.google.com" &&
      url.pathname === "/search" &&
      Boolean(url.searchParams.get("q"));
  } catch {
    return false;
  }
}

chrome.tabs.onRemoved.addListener(async (tabId) => {
  const { managedSearchTabId } = await chrome.storage.local.get("managedSearchTabId");
  if (managedSearchTabId === tabId) {
    await chrome.storage.local.remove("managedSearchTabId");
  }
});
