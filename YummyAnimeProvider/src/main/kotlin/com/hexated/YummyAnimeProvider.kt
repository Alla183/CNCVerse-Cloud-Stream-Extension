package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.models.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName


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


// -------------------------
// JSON DTO для епізодів
// -------------------------
    data class EpisodesResponse(
        val data: List<EpisodeDto>?
    )

    data class EpisodeDto(
        val id: String?,
        val number: Int?,
        val title: String?
    )

// -------------------------
// Load епізодів
// -------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

    // Назва
        val title = doc.selectFirst("div.titles > h1")?.text() ?: "No title"

    // Обкладинка
        val poster = doc.selectFirst("div.poster-block img")?.attr("src") ?: ""

    // Опис
        val plot = doc.selectFirst("p[itemprop=description]")?.text()

    // -------------------------
    // Епізоди через Kodik API
    // -------------------------
        val episodes = mutableListOf<Episode>()
        try {
        // Формуємо API URL
            val episodeUrl = url.removePrefix(baseUrl)
            val urlParts = episodeUrl.split("/") // наприклад "vertex-force/episodes"
            if (urlParts.size < 2) return newAnimeLoadResponse(title, url) {
                this.posterUrl = poster
                this.description = plot
            }

            val animeSlug = urlParts[0]
            val apiUrl = "https://api.yani.tv/anime/$animeSlug/episodes"
            println("Fetching episodes from: $apiUrl")

        // Запит до API
            val client = OkHttpClient()
            val request = Request.Builder().url(apiUrl).build()
            val response = client.newCall(request).execute()
            val jsonResponse = response.body?.string() ?: ""
            println("Response: $jsonResponse")

        // Десеріалізація
            val gson = Gson()
            val episodesResponse = gson.fromJson(jsonResponse, EpisodesResponse::class.java)

            episodesResponse.data?.forEach { ep ->
                val episode = newEpisode(
                    data = ep.id ?: "",
                    name = ep.title ?: "Episode ${ep.number ?: "?"}"
                )
                episodes.add(episode)
                println("Found episode: ${episode.name} | data: ${episode.data}")
            }
        } catch (e: Exception) {
            println("Error loading episodes: ${e.message}")
        }

        return newAnimeLoadResponse(title, url) {
            this.posterUrl = poster
            this.description = plot
            this.episodes = episodes
        }
    }

// -------------------------
// LoadLinks через Kodik
// -------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val client = OkHttpClient()
            val embedUrl = "https://api.yani.tv/episodes/$data/players" // data = episode id
            val request = Request.Builder().url(embedUrl).build()
            val response = client.newCall(request).execute()
            val jsonResponse = response.body?.string() ?: ""

        // JSON десеріалізація
            val gson = Gson()
            val playersResponse = gson.fromJson(jsonResponse, PlayersResponse::class.java)

            playersResponse.data?.forEach { player ->
                player.embeds?.forEach { embed ->
                    val embedUrl = embed.url ?: return@forEach
                    if (embedUrl.contains("kodik", true)) {
                        val videos = extractKodik(embedUrl)
                        videos.forEach { video ->
                            callback.invoke(
                                ExtractorLink(
                                    name = "Kodik - ${video.quality}",
                                    url = video.url,
                                    referer = baseUrl,
                                    isM3u8 = video.url.contains(".m3u8")
                                )
                            )
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            println("Error in loadLinks: ${e.message}")
            return false
        }
    }

// -------------------------
// Kodik extractor
// -------------------------
    private fun extractKodik(embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val client = OkHttpClient()
            val headers = Headers.Builder().add("Referer", baseUrl).build()
            val request = Request.Builder().url(embedUrl).headers(headers).build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return videos

            val kodikMatch = Regex("\"url\":\"([^\"]+)\"").find(html)
            kodikMatch?.groupValues?.getOrNull(1)?.let { url ->
                val decodedUrl = url.replace("\\u0026", "&")
                val absoluteUrl = if (decodedUrl.startsWith("http")) decodedUrl else "$baseUrl$decodedUrl"
                videos.add(
                    Video(
                        url = absoluteUrl,
                        quality = "HD",
                        videoUrl = absoluteUrl
                    )
                )
            }
        } catch (_: Exception) { }
        return videos
    }

// -------------------------
// DTO для Kodik players
// -------------------------
    data class PlayersResponse(
        val data: List<PlayerDto>?
    )

    data class PlayerDto(
        val embeds: List<EmbedDto>?
    )

    data class EmbedDto(
        val url: String?,
        val player: PlayerInfoDto?
    )

    data class PlayerInfoDto(
        val name: String?
    )

// -------------------------
// Video DTO
// -------------------------
    data class Video(
        val url: String,
        val quality: String,
        val videoUrl: String
    )
}
