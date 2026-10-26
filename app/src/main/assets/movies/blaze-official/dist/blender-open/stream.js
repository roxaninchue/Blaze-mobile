"use strict";
// Blaze Official example provider: the item's own MP4 / Ogg video files and subtitle files on archive.org.
Object.defineProperty(exports, "__esModule", { value: true });
exports.getStream = async function (o) {
    var id = String(o.link).split("/details/")[1].split(/[/?#]/)[0];
    var res = await o.providerContext.axios.get("https://archive.org/metadata/" + encodeURIComponent(id));
    var files = (res.data && res.data.files) || [];
    function url(n) { return "https://archive.org/download/" + id + "/" + n.split("/").map(encodeURIComponent).join("/"); }
    var subs = files.filter(function (f) { return /\.(vtt|srt)$/i.test(f.name); }).map(function (f) {
        return { title: /\.asr\./.test(f.name) ? "Auto captions" : f.name, language: "en", type: /\.srt$/i.test(f.name) ? "application/x-subrip" : "text/vtt", uri: url(f.name) };
    });
    var out = [];
    files.forEach(function (f) {
        var fmt = String(f.format || "").toLowerCase();
        if (/\.(mp4|m4v)$/i.test(f.name) && /(mpeg4|h\.264|mp4)/.test(fmt)) out.push({ server: /512kb/.test(fmt) ? "Archive (small)" : "Archive", link: url(f.name), type: "mp4", quality: f.height ? String(f.height) : "", subtitles: subs });
        else if (/\.ogv$/i.test(f.name)) out.push({ server: "Archive (Ogg)", link: url(f.name), type: "ogv", quality: f.height ? String(f.height) : "", subtitles: subs });
    });
    return out;
};
