const target = new URLSearchParams(window.location.search).get("target");

if (target) {
  chrome.runtime.sendMessage(
    { type: "markusaller-search", target },
    (response) => {
      if (chrome.runtime.lastError || !response?.ok) {
        document.title = "Markusaller-Suche konnte nicht übergeben werden";
        document.querySelector("h1").textContent = "Comet-Erweiterung nicht erreichbar";
        document.querySelector("body").insertAdjacentText(
          "beforeend",
          " Die Erweiterung ist nicht aktiv. Du kannst den Google-Link oben manuell öffnen."
        );
      }
    }
  );
}
