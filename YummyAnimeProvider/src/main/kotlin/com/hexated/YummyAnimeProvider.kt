package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue


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


    data class AnimeItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("anime_url") val animeUrl: String? = null,
        @JsonProperty("poster") val poster: Poster? = null,
        @JsonProperty("description") val description: String? = null
    )

    data class Poster(
        @JsonProperty("fullsize") val fullsize: String? = null
    )

    data class ApiResponse(
        @JsonProperty("response") val response: List<AnimeItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.yani.tv/search?q=$encoded&offset=0&limit=20"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        println("YUMMY SEARCH URL: $url")
        showToast("Search: $query")

        return try {
            val responseBody = app.get(url, headers).toString()

            println("YUMMY RESPONSE: $responseBody")

            val json = org.json.JSONObject(responseBody)
            val responseArray = json.optJSONArray("response")

            println("YUMMY ARRAY SIZE: ${responseArray?.length()}")

            if (responseArray == null || responseArray.length() == 0) {
                showToast("❌ Нічого не знайдено")
                return emptyList()
            }

            val results = mutableListOf<SearchResponse>()

            for (i in 0 until responseArray.length()) {
                val obj = responseArray.optJSONObject(i) ?: continue

                val title = obj.optString("title", "")
                val animeUrl = obj.optString("anime_url", "")
                val poster = obj.optJSONObject("poster")?.optString("fullsize") ?: ""

                println("ITEM: $title | $animeUrl")

                if (title.isBlank() || animeUrl.isBlank()) continue

                results.add(
                    newAnimeSearchResponse(
                        title,
                        "https://site.yummyani.me/catalog/item/$animeUrl",
                        TvType.Anime
                    ) {
                        this.posterUrl = poster
                    }
                )
            }

            showToast("✅ Знайдено: ${results.size}")

            results

        } catch (e: Exception) {
            e.printStackTrace()
            println("YUMMY ERROR: ${e.message}")
            showToast("❌ Error search")
            emptyList()
        }
    }

    // =========================
    // 📄 Деталі + епізоди
    // =========================
    data class AnimeResponse(
        @JsonProperty("response") val response: AnimeDetails? = null
    )
    
    data class AnimeDetails(
        @JsonProperty("episodes") val episodes: List<Episode>? = null
    )

    data class Episode(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null
    )
    
    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")

        val apiUrl = "https://api.yani.tv/anime/$slug"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        val responseBody = app.get(apiUrl, headers).toString()

        val mapper = jacksonObjectMapper()
        val apiResponse = mapper.readValue<AnimeResponse>(responseBody)

        val data = apiResponse.response

        val data = res.response ?: run {
            println("❌ No response from API")
            return null
        }

        val episodes = data.episodes?.map { ep ->
            newEpisode(
                data = ep.id.toString()
        ) {
            name = "Episode ${ep.episode}"
        }
    } ?: emptyList())

        return newAnimeLoadResponse(
            name = slug,
            url = url,
            type = TvType.Anime
        ) {
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

        val episodeId = data

        val url = "https://api.yani.tv/episode/$episodeId"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json"
        )

        val response = app.get(url, headers).toString()

        val json = JSONObject(response)

        val streams = json.optJSONArray("streams") ?: return false

        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue

            val videoUrl = stream.optString("url") ?: continue
            val quality = stream.optString("quality") ?: "HD"

            callback.invoke(
                newExtractorLink(
                    source = "YummyAnime",
                    name = "YummyAnime $quality",
                    url = videoUrl,
                    type = INFER_TYPE
                )
            )
        }

        return true
    }
}
