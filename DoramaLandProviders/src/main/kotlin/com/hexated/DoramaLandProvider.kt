package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class DoramaLandProvider : MainAPI() {

    override var mainUrl = "https://dorama.land"
    override var name = "DoramaLand"
    override var lang = "uk"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/all-dramas" to "Усі дорами"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/all-dramas?page=$page").document

        val home = doc.select("div.catalog-item.catalog-item_type_poster").map { el ->
            val title = el.selectFirst("div.catalog-item__title")?.text() ?: "No title"
            val href = fixUrl(el.selectFirst("a")?.attr("href") ?: "")
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document

        return doc.select(".search-item").map { element ->
            val title = element.selectFirst(".search-item__title")?.text() ?: "No title"
            val href = fixUrl(element.selectFirst(".search-item-wrap")?.attr("href") ?: "")
            val poster = fixUrlNull(element.selectFirst(".search-item-img img")?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(
            data,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to mainUrl
            ),
            timeout = 30
        ).document

        val title = doc.selectFirst("h1")?.text() ?: "No title"
        val poster = fixUrlNull(doc.selectFirst(".about-serial-poster img")?.attr("src"))
        val description = doc.selectFirst(
            ".serial-description-text .spoiler__content[itemprop=description]"
        )?.text()

        val episodes = doc.select("div.catalog.serial-list-episodes div.short-cinematic")
            .mapNotNull { ep ->
                val href = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val name = ep.selectFirst(".short-cinematic__episode-number")?.text()

                val episode = Regex("(\\d+)").find(name ?: "")
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(fixUrl(href)) {
                    this.name = name
                    this.episode = episode
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(
            data,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
                "Referer" to mainUrl,
                "Accept" to "text/html"
            )
        ).document

        val jsonText = doc.selectFirst("#inputData")?.html() ?: return false

        val episodeNumber = Regex("(\\d+)-seriya")
            .find(data)
            ?.groupValues?.getOrNull(1)
            ?: return false

        val json = JSONObject(jsonText)

        val season = json.getJSONObject("1")

        if (!season.has(episodeNumber)) return false

        val voices = season.getJSONArray(episodeNumber)

        for (i in 0 until voices.length()) {

            val item = voices.getJSONObject(i)

            val videoId = item.getString("video_id")
            val voiceName = item.getString("voice_name")
            val voiceTag = item.getString("voice_tag")

            for (server in 1..5) {

                val m3u8 =
                    "https://s$server.jaswish.com/hls/$videoId/$voiceTag/index.m3u8"

                callback.invoke(
                    newExtractorLink(
                        "DoramaLand",
                        voiceName,
                        m3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        referer = "https://a.jaswish.com/"
                        headers = mapOf(
                            "Origin" to "https://a.jaswish.com",
                            "Referer" to "https://a.jaswish.com/",
                            "User-Agent" to "Mozilla/5.0"
                        )
                    }
                )
            }
        }

        return true
    }
}
