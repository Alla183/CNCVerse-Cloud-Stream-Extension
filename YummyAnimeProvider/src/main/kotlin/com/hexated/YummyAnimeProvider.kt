package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper


class YummyAnimeProvider : MainAPI() {

    override var name = "YummyAnime"
    override var mainUrl = "https://site.yummyani.me"
    override var lang = "ru"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    // =========================
    // 🔥 Головна сторінка
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/catalog?page=$page"
        val doc = app.get(url).document

        val list = doc.select("div.anime-column").mapNotNull { element ->
            val link = element.selectFirst("a.image-block")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            val title = element.selectFirst(".anime-title")?.text() ?: return@mapNotNull null

            newAnimeSearchResponse(
                title,
                mainUrl + link,
                TvType.Anime
            ) {
                posterUrl = fixUrl(poster)
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Усі аніме", list)),
            list.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8.toString())
        val url = "$mainUrl/search?word=$encodedQuery"
        val doc = app.get(url).document

        return doc.select("div.anime-column").mapNotNull { element ->
            val link = element.selectFirst("a.image-block")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            val title = element.selectFirst(".anime-title")?.text() ?: return@mapNotNull null

            newAnimeSearchResponse(
                title,
                mainUrl + link,
                TvType.Anime
            ) {
                posterUrl = fixUrl(poster)
            }
        }
    }

    // =========================
    // 📄 Деталі + епізоди
    // =========================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

    // Назва
        val title = doc.selectFirst("div.titles > h1")?.text() ?: "No title"

    // Обкладинка
        val poster = doc.selectFirst("div.poster-block img")?.attr("src") ?: ""

    // Опис
        val plot = doc.selectFirst("p[itemprop=description]")?.text()

    // Серії (на даний момент можна додати iframe як приклад)
        val episodes = mutableListOf<Episode>()
        val iframe = doc.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) {
            episodes.add(
                newEpisode(iframe) {
                    name = "Episode 1"
                    episode = 1
                }
            )
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = fixUrl(poster)
            this.plot = plot
            this.episodes = mutableMapOf(
            DubStatus.Subbed to episodes
            )
        }
    }

    // =========================
    // 🎥 Відео
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
