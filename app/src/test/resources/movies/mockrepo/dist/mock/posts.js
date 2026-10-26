"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : new P(function (r) { r(result.value); }).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.getSearchPosts = exports.getPosts = void 0;
function parse(html, cheerio, base) {
    const $ = cheerio.load(html);
    const out = [];
    $(".film").each((i, el) => {
        const a = $(el).find("a");
        out.push({ title: a.text().trim(), link: new URL(a.attr("href"), base).href, image: $(el).find("img").attr("src") || "" });
    });
    return out;
}
const getPosts = function ({ filter, page, providerValue, signal, providerContext }) {
    return __awaiter(this, void 0, void 0, function* () {
        const base = yield providerContext.getBaseUrl(providerValue);
        const res = yield providerContext.axios.get(base + filter + "?page=" + page, { headers: providerContext.commonHeaders });
        return parse(res.data, providerContext.cheerio, base);
    });
};
exports.getPosts = getPosts;
const getSearchPosts = async function ({ searchQuery, page, providerContext }) {
    const base = await providerContext.getBaseUrl("mock");
    const r = await fetch(base + "/search?q=" + encodeURIComponent(searchQuery));
    if (!r.ok) throw new Error("search failed " + r.status);
    return parse(await r.text(), providerContext.cheerio, base);
};
exports.getSearchPosts = getSearchPosts;
