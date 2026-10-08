// GitHub Pages serves this fallback for paths without a physical file.
(function () {
  try {
    sessionStorage.setItem("redirect", location.href);
    location.replace("/");
  } catch {
    // App views/auth use query and hash parameters. Preserve them even when
    // storage is disabled; the unsupported pathname cannot be restored.
    location.replace("/" + location.search + location.hash);
  }
})();
