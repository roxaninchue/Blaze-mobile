"use strict";
// Blaze Official example provider: title details from archive.org's metadata API.
Object.defineProperty(exports, "__esModule", { value: true });
exports.getMeta = async function (o) {
    var id = String(o.link).split("/details/")[1].split(/[/?#]/)[0];
    var res = await o.providerContext.axios.get("https://archive.org/metadata/" + encodeURIComponent(id));
    var md = (res.data && res.data.metadata) || {};
    function one(v) { return Array.isArray(v) ? (v[0] || "") : (v || ""); }
    var desc = o.providerContext.cheerio.load("<div>" + one(md.description) + "</div>")("div").text().trim();
    return { title: one(md.title) || id, synopsis: desc, image: "https://archive.org/services/img/" + id, type: "movie",
        tags: [one(md.year)].filter(Boolean), linkList: [{ title: "Watch", directLinks: [{ title: "Play", link: o.link, type: "movie" }] }] };
};
