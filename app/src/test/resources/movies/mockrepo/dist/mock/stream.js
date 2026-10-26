"use strict";
exports.getStream = async function ({ link, type, providerContext }) {
    const last = await providerContext.kvStore.get("last");
    const leaked = typeof require("fs").readFileSync === "function" || typeof globalThis !== "undefined";
    return [
        { server: "Main", link: link.replace("/play", "/video.mp4"), type: "mp4", quality: "720", subtitles: [{ title: "English", language: "en", type: "text/vtt", uri: link.replace("/play", "/en.vtt") }], headers: { Referer: link } },
        { server: "Low", link: link.replace("/play", "/low.mp4"), type: "mp4", quality: "360" },
        { server: "Bad", link: "javascript:alert(1)", type: "mp4" },
        { server: last || "none", link: "https://example.invalid/x.m3u8", quality: leaked ? "leak" : "" }
    ];
};
