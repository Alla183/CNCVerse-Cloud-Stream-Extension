package com.hexated

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

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
        "$mainUrl/doramy/" to "Dorama",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get("${request.data}?page=$page").document

        val home = doc.select(".poster-item").map {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {

        val title = this.selectFirst(".poster-item__title")?.text() ?: "No title"
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: "")
        val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/search?q=$query").document

        return doc.select(".poster-item").map {
            it.toSearchResult()
        }
    }

   override suspend fun load(url: String): LoadResponse {

       val doc = app.get(url).document

       val title = doc.selectFirst("h1")?.text() ?: "No title"
       val poster = fixUrlNull(doc.selectFirst(".poster img")?.attr("src"))
       val description = doc.selectFirst(".description")?.text()

    // 🔹 ОТ ТУТ додаємо парсинг епізодів
       val episodes = doc.select(
           "div.catalog.serial-list-episodes div.short-cinematic"
       ).mapNotNull { ep ->

           val href = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
           val name = ep.selectFirst(".short-cinematic__episode-number")?.text()

           val episode =
               Regex("(\\d+)").find(name ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

           newEpisode(fixUrl(href)) {
               this.name = name
               this.episode = episode
           }
       }

    // 🔹 тут використовуємо episodes
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

    val document = app.get(data).document

    val episode =
        Regex("(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "1"

    val voices = document.select("#filterV option")

    voices.forEach { voice ->

        val voiceId = voice.attr("value")
        val voiceName = voice.text()

        val iframeUrl =
            "https://a.jaswish.com/pkybuen7l6vw5ars?v=$voiceId&s=$episode"

        val player = app.get(iframeUrl).text

        val m3u8 =
            Regex("""https://s\d+\.jaswish\.com[^\s"']+index\.m3u8""")
                .find(player)
                ?.value

        if (m3u8 != null) {

            callback.invoke(
                newExtractorLink(
                    "DoramaLand",
                    voiceName,
                    m3u8,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://a.jaswish.com/"
                    this.headers = mapOf(
                        "Origin" to "https://a.jaswish.com",
                        "Referer" to "https://a.jaswish.com/"
                    )
                }
            )
        }
    }

    return true
}
}
