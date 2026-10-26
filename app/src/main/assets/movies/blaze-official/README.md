# Blaze Official providers (example repo)

Blaze's own example of a provider repo in the Vega provider format: free, legal films only
(Internet Archive public-domain feature films and the Blender Foundation's open movies, CC BY).

    manifest.json                     list of providers: value, display_name, version, icon, type
    dist/<value>/catalog.js           exports catalog [{title, filter}] and genres
    dist/<value>/posts.js             exports getPosts({filter, page, providerContext}) and getSearchPosts({searchQuery, page, providerContext})
    dist/<value>/meta.js              exports getMeta({link, providerContext}) -> {title, synopsis, image, type, linkList}
    dist/<value>/stream.js            exports getStream({link, type, providerContext}) -> [{server, link, type, quality, subtitles}]
    dist/<value>/episodes.js          optional: getEpisodes({url, providerContext}) -> [{title, link}]

Public copy (what Blaze installs): https://blazeinchue.pages.dev/providers/blaze-official/
Format docs: https://blazeinchue.pages.dev/providers/

Code in this repository: MIT licence. The films belong to their owners; this repo only links to them on archive.org.
