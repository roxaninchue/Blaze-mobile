"use strict";
exports.getEpisodes = async function ({ url, providerContext }) {
    const r = await providerContext.axios.get(url);
    return r.data.map(function (e, i) { return { title: "Episode " + (i + 1), link: e }; });
};
