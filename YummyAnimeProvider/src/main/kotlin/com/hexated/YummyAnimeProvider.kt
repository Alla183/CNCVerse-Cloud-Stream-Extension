package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

        val list = doc.select("a.image-block").mapNotNull { element ->
            val link = element.attr("href")
            val poster = element.select("img").attr("src")

            val parent = element.parent()
            val title = parent.select(".anime-title").text()

            if (title.isNullOrBlank()) return@mapNotNull null

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

    // =========================
    // 🔎 Пошук
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/catalog?search=$query"

        val doc = app.get(url).document

        return doc.select("a.image-block").mapNotNull { element ->
            val link = element.attr("href")
            val poster = element.select("img").attr("src")

            val parent = element.parent()
            val title = parent.select(".anime-title").text()

            if (title.isNullOrBlank()) return@mapNotNull null

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

        val title = doc.selectFirst("h1")?.text() ?: "No title"
        val poster = doc.selectFirst("img")?.attr("src")

        // 🔥 тут треба буде знайти iframe або список серій
        val iframe = doc.selectFirst("iframe")?.attr("src")

        val episodes = mutableListOf<Episode>()

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
            posterUrl = fixUrl(poster ?: "")
            plot = doc.selectFirst(".description")?.text()

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
