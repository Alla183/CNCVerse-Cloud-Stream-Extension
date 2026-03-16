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

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, emptyList()) {
            this.posterUrl = poster
            this.plot = description
        }
    }
}
