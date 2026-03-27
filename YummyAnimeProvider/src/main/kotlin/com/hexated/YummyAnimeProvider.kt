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
                val posterRaw = obj.optJSONObject("poster")?.optString("fullsize") ?: ""
                val poster = if (posterRaw.startsWith("//")) "https:$posterRaw" else posterRaw

                println("POSTER FIXED: $poster")
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
        try {
            val urlParts = url.split("/") // розбиваємо URL на частини
            if (urlParts.isEmpty()) return LoadResponse.error("Invalid URL")

            val animeSlug = urlParts.last() // наприклад "vertex-force"
            val apiUrl = "https://api.yani.tv/anime/$animeSlug/episodes"

            println("Fetching episodes from: $apiUrl")

            val response = app.get(apiUrl)
            val jsonResponse = response.toString()
            println("Response: $jsonResponse")

        // Десеріалізуємо JSON
            val episodesResponse = gson.fromJson(jsonResponse, EpisodesResponse::class.java)
            episodesResponse.data?.forEach { ep ->
                val episode = Episode(
                    name = ep.title ?: "Episode ${ep.number ?: "?"}",
                    url = "$animeSlug/episodes/${ep.id ?: ""}",
                    episodeNumber = ep.number?.toFloat() ?: 0f
                )
                println("Found episode: ${episode.name} | URL: ${episode.url}")
                episodes.add(episode)
            }

        } catch (e: Exception) {
            println("Error loading episodes: ${e.message}")
        }

    // Повертаємо LoadResponse
        return newAnimeLoadResponse(
            title = title,
            url = url,
            posterUrl = poster,
            plot = plot,
            episodes = episodes
        )
    }

    // =========================
    // 🎥 Відео
    // =========================
    override suspend fun loadLinks(episode: Episode): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val urlParts = episode.url.split("/")
            if (urlParts.size < 4) return videos

            val animeSlug = urlParts[0]
            val episodeId = urlParts[3]
            val apiUrl = "https://api.yani.tv/anime/$animeSlug/episodes/$episodeId/players"

            println("Fetching Kodik videos for episode: ${episode.name}")
            println("API URL: $apiUrl")

            val response = app.get(apiUrl)
            val jsonResponse = response.toString()
            println("Video response: $jsonResponse")

            val playersResponse = gson.fromJson(jsonResponse, PlayersResponse::class.java)
            playersResponse.data?.forEach { player ->
                player.embeds?.forEach { embed ->
                    val embedUrl = embed.url ?: return@forEach
                // Використовуємо лише Kodik
                    if (embedUrl.contains("kodik", true)) {
                        println("Found Kodik embed: $embedUrl")
                        videos.addAll(extractKodik(embedUrl))
                    }
                }
            }

        } catch (e: Exception) {
            println("Error loading Kodik videos: ${e.message}")
        }

        return videos
    }

// =========================
// 🔧 Extract Kodik Video
// =========================
    private fun extractKodik(embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val headers = Headers.Builder()
                .add("Referer", "https://yummyani.me") // обов’язково, щоб Kodik відпустив запит
                .build()

            val response = client.newCall(GET(embedUrl, headers)).execute()
            val html = response.body?.string() ?: return videos

        // Витягаємо URL відео з HTML
            val kodikMatch = Regex("\"url\":\"([^\"]+)\"").find(html)
            kodikMatch?.groupValues?.getOrNull(1)?.let { url ->
                val decodedUrl = url.replace("\\u0026", "&")
                val absoluteUrl = if (decodedUrl.startsWith("http")) decodedUrl else "https://kodik.info$decodedUrl"

                println("Kodik video URL: $absoluteUrl")

                videos.add(
                    Video(
                        url = absoluteUrl,
                        quality = "Kodik - HD",
                        videoUrl = absoluteUrl,
                    )
                )
            }

        } catch (e: Exception) {
            println("Error extracting Kodik video: ${e.message}")
        }

        return videos
    }
}
