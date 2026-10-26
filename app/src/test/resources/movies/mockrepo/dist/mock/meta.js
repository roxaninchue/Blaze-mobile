"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.getMeta = async function ({ link, providerContext }) {
    const res = await providerContext.axios.get(link);
    const j = res.data;
    await providerContext.kvStore.set("last", j.title);
    return { title: j.title, synopsis: j.plot, image: j.poster, type: j.series ? "series" : "movie", tags: ["Public domain"],
        linkList: j.series ? [{ title: "Season 1", episodesLink: link + "/episodes" }] : [{ title: "Movie", quality: "720", directLinks: [{ title: "Play", link: link + "/play", type: "movie" }] }] };
};
