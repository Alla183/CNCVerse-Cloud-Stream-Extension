package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast

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
        showToast("LOAD: start")

        val res = app.get(url)
        println("FINAL URL: ${res.url}")
        val doc = res.document

        showToast("LOAD: page loaded")

        val title = doc.selectFirst("h1")?.text() ?: "No title"
        println("TITLE: $title")

        val poster = fixUrlNull(doc.selectFirst(".about-serial-poster img")?.attr("src"))

        val description = doc.selectFirst(
            ".serial-description-text .spoiler__content[itemprop=description]"
        )?.text()

        val episodeElements = doc.select(".short-cinematic")
        println("EP ELEMENTS SIZE: ${episodeElements.size}")

        val episodes = episodeElements.mapNotNull { ep ->
            val hrefRaw = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            val cleanHref = hrefRaw
                .replace("\\s+".toRegex(), "")   // 💥 КЛЮЧОВИЙ ФІКС
                .trim()

            val fixed = fixUrl(cleanHref)

            println("FIXED CLEAN: $fixed")



            val name = ep.selectFirst(".short-cinematic__episode-number")?.text()

            val episode = Regex("(\\d+)")
                .find(name ?: "")
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()

            newEpisode(fixed) {
                this.name = name
                this.episode = episode

                this.data = fixed
            }
        }

        showToast("LOAD: episodes = ${episodes.size}")

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
        showToast("LOADLINKS")
        
        val doc = app.get(data).document

        val iframe = doc.selectFirst("iframe") ?: run {
            showToast("iframe не знайдено")
            return false
        }

        val iframeUrl = fixUrl(iframe.attr("src"))
        val iframeDoc = app.get(iframeUrl, referer = mainUrl).document

        val playerDiv = iframeDoc.selectFirst("div[id^=videoplayer]") ?: run {
            showToast("Player не знайдено в iframe")
            return false
        }

        val dataConfigRaw = playerDiv.attr("data-config")
        if (dataConfigRaw.isNullOrEmpty()) {
            showToast("data-config порожній")
            return false
        }

        val dataConfig = JSONObject(dataConfigRaw)
        val hls = dataConfig.optString("hls")

        if (hls.isNullOrEmpty()) {
            showToast("HLS не знайдено")
            return false
        }

        callback.invoke(
            newExtractorLink(
                "DoramaLand",
                "Main",
                hls,
                ExtractorLinkType.M3U8
            ) {
                this.referer = iframeUrl
            }
        )

        return true
    }
}
