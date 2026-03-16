package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
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
       val doc = app.get(url).document

       val title = doc.selectFirst("h1")?.text() ?: "No title"

       val poster = fixUrlNull(doc.selectFirst(".about-serial-poster img")?.attr("src"))

       val description = doc.selectFirst(
           ".serial-description-text .spoiler__content[itemprop=description]"
       )?.text()

       val episodes = doc.select("div.catalog.serial-list-episodes div.short-cinematic")
           .mapNotNull { ep ->
               val href = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
               val name = ep.selectFirst(".short-cinematic__episode-number")?.text()
               val episode = Regex("(\\d+)").find(name ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

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

    val doc = app.get(data).document

    val players = doc.select(".tabs-list__item")

    players.forEach { player ->

        val name = player.selectFirst("h3")?.text() ?: "Voice"

        val iframe = player.attr("data-url-player")
        val iframeUrl = fixUrl(iframe)

        val iframePage = app.get(iframeUrl).text

        val m3u8 = Regex("""https://s\d+\.jaswish\.com[^\s"']+index\.m3u8""")
            .find(iframePage)
            ?.value

        if (m3u8 != null) {

            callback.invoke(
                newExtractorLink(
                    "DoramaLand",
                    name,
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
