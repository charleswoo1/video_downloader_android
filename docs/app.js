(() => {
  const repo = "charleswoo1/video_downloader_android";
  const fallback = {
    version: "v1.0.0",
    apk: "https://github.com/charleswoo1/video_downloader_android/releases/download/v1.0.0/SocialVideoDownloader-Android-v1.0.0.apk",
    sums: "https://github.com/charleswoo1/video_downloader_android/releases/download/v1.0.0/SHA256SUMS.txt",
    digest: "83e67c1fbff756ca2460d639f8a98aee9fa1e4cc4b80755eb19d6970ba604f3a"
  };

  const setRelease = ({ version, apk, sums, digest }) => {
    const versionLabel = document.querySelector("#versionLabel");
    const downloadButton = document.querySelector("#downloadButton");
    const checksumVersion = document.querySelector("#checksumVersion");
    const checksumLink = document.querySelector("#checksumLink");
    const checksumValue = document.querySelector("#checksumValue");

    versionLabel.textContent = `正式版 ${version}`;
    downloadButton.href = apk;
    downloadButton.setAttribute("aria-label", `下載 Social Video Downloader ${version} Android APK`);
    checksumVersion.textContent = `${version} SHA-256`;
    checksumLink.href = sums;
    if (digest) checksumValue.textContent = digest.replace(/^sha256:/, "");
  };

  setRelease(fallback);

  fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
    headers: { Accept: "application/vnd.github+json" }
  })
    .then(response => {
      if (!response.ok) throw new Error("Release API unavailable");
      return response.json();
    })
    .then(release => {
      const apkAsset = release.assets?.find(asset => asset.name?.toLowerCase().endsWith(".apk"));
      const sumAsset = release.assets?.find(asset => asset.name === "SHA256SUMS.txt");
      if (!release.tag_name || !apkAsset) return;

      setRelease({
        version: release.tag_name,
        apk: apkAsset.browser_download_url,
        sums: sumAsset?.browser_download_url || fallback.sums,
        digest: apkAsset.digest || (release.tag_name === fallback.version ? fallback.digest : "")
      });
    })
    .catch(() => {
      // Keep the verified fallback release shown in the static HTML.
    });
})();
