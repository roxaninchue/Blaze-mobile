"use strict";
// Blaze Official example provider: lists items from archive.org's public search API.
Object.defineProperty(exports, "__esModule", { value: true });
exports.getSearchPosts = exports.getPosts = void 0;
var BASE = "(collection:feature_films OR (collection:moviesandfilms AND licenseurl:*publicdomain*)) AND mediatype:movies";
function words(q) { return String(q || "").replace(/[():"\\\[\]{}^~*?]/g, " ").replace(/\s+/g, " ").trim(); }
function query(filter, search) {
    var q = BASE;
    if (filter) q = "(" + filter + ") AND " + q;
    var w = words(search);
    if (w) q = "(" + w + ") AND " + q;
    return q;
}
async function list(q, page, providerContext) {
    var url = "https://archive.org/advancedsearch.php?q=" + encodeURIComponent(q) + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
        "&sort%5B%5D=" + encodeURIComponent("downloads desc") + "&rows=30&page=" + (page || 1) + "&output=json";
    var res = await providerContext.axios.get(url);
    var docs = (res.data && res.data.response && res.data.response.docs) || [];
    return docs.map(function (d) {
        return { title: String(d.title || d.identifier), tag: d.year ? String(d.year) : "", link: "https://archive.org/details/" + d.identifier, image: "https://archive.org/services/img/" + d.identifier };
    });
}
exports.getPosts = async function (o) { return list(query(o.filter, ""), o.page, o.providerContext); };
exports.getSearchPosts = async function (o) { return list(query("", o.searchQuery), o.page, o.providerContext); };
