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
import okhttp3.FormBody
import android.net.Uri
import android.util.Base64


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

    // 🔥 отримуємо anime_url
        val animeUrl = url.substringAfterLast("/")

        val apiUrl = "https://api.yani.tv/anime/$animeUrl?need_videos=true"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        val json = JSONObject(app.get(apiUrl, headers).toString())
        val response = json.optJSONObject("response")

        val videos = response?.optJSONArray("videos")

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        if (videos != null) {
            for (i in 0 until videos.length()) {
                val obj = videos.optJSONObject(i) ?: continue

                val number = obj.optString("number") ?: continue

                if (number in seen) continue
                seen.add(number)

                episodes.add(
                    newEpisode("$animeUrl|$number") {
                    name = "Серія $number"
                    episode = number.toFloatOrNull()?.toInt()
                    }
                )
            }
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = fixUrl(poster)
            this.plot = plot

        // 🔥 ВАЖЛИВО: саме так CloudStream очікує episodes
            this.episodes = mutableMapOf(
                DubStatus.Subbed to episodes.sortedBy { it.episode }
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

        println("LOADLINKS DATA: $data")
        showToast("loadLinks start")

        val (rawUrl, episodeNumber) = data.split("|")
        val animeUrl = rawUrl.substringAfterLast("/")
        
        println("ANIME SLUG: $animeUrl")
        showToast(animeUrl)

        val apiUrl = "https://api.yani.tv/anime/$animeUrl?need_videos=true&episode=$episodeNumber"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        val jsonText = app.get(apiUrl, headers).text
        println("API RESPONSE: ${jsonText.take(300)}")

        val json = JSONObject(jsonText)
        val response = json.optJSONObject("response")

        if (response == null) {
            showToast("❌ response null")
            return false
        }

        val videos = response.optJSONArray("videos")

        if (videos == null) {
            showToast("❌ videos null")
            return false
        }

        println("VIDEOS COUNT: ${videos.length()}")
        
        for (i in 0 until videos.length()) {
            val obj = videos.optJSONObject(i) ?: continue

            val number = obj.optString("number")
            if (number != episodeNumber) continue

            val iframe = fixUrl(obj.optString("iframe_url"))
            val player = obj.optJSONObject("data")?.optString("player") ?: ""

            println("FOUND EPISODE: $number PLAYER: $player")
            showToast("iframe found")

            if (!player.contains("Kodik", true)) {
                println("SKIP player: $player")
                continue
            }

            val extracted = extractKodikVideos(iframe)

            println("EXTRACTED COUNT: ${extracted.size}")

            if (extracted.isEmpty()) {
                showToast("❌ no videos extracted")
            }

            extracted.forEach {
                println("VIDEO: ${it.first}")

                callback.invoke(
                    newExtractorLink(
                        source = "Kodik",
                        name = it.second,
                        url = it.first,
                        type = if (it.first.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(it.second)
                    }
                )
            }
        }

        showToast("loadLinks done")
        return true
    }

    suspend fun extractKodikVideos(iframeUrl: String): List<Pair<String, String>> {

        println("IFRAME: $iframeUrl")
        showToast("fetch iframe")

        val page = app.get(iframeUrl).text

        println("PAGE SIZE: ${page.length}")

        val type = extractAfter(page, "vInfo.type =")
        val hash = extractAfter(page, "vInfo.hash =")
        val id = extractAfter(page, "vInfo.id =")
        val rawParams = extractAfter(page, "var urlParams =")

        println("TYPE: $type")
        println("HASH: $hash")
        println("ID: $id")

        if (type == null || hash == null || id == null) {
            showToast("❌ context parse fail")
            return emptyList()
        }

        val params = try {
            JSONObject(rawParams!!)
        } catch (e: Exception) {
            println("PARAMS PARSE ERROR: ${e.message}")
            showToast("❌ params error")
            return emptyList()
        }

        val endpoint = detectEndpoint(page) ?: "/ftor"

        println("ENDPOINT: $endpoint")
        showToast("endpoint: $endpoint")

        val body = FormBody.Builder()
            .add("hash", hash)
            .add("id", id)
            .add("type", type)
            .add("d", params.optString("d"))
            .add("d_sign", params.optString("d_sign"))
            .add("pd", params.optString("pd"))
            .add("pd_sign", params.optString("pd_sign"))
            .add("ref", Uri.decode(params.optString("ref")))
            .add("ref_sign", params.optString("ref_sign"))
            .add("bad_user", "true")
            .add("cdn_is_working", "true")
            .build()

        val linkJsonText = app.post(
            url = "https://kodik.cc$endpoint",
            requestBody = body
        ).text

        println("KODIK RESPONSE: ${linkJsonText.take(300)}")

        val json = JSONObject(linkJsonText)
        val links = json.optJSONObject("links")

        if (links == null) {
            showToast("❌ no links")
            return emptyList()
        }

        val result = mutableListOf<Pair<String, String>>()

        links.keys().forEach { quality ->
            val arr = links.optJSONArray(quality) ?: return@forEach

            println("QUALITY: $quality COUNT: ${arr.length()}")

            for (i in 0 until arr.length()) {
                val src = arr.getJSONObject(i).optString("src")

                println("ENCODED SRC: ${src.take(50)}")

                val decoded = decodeKodik(src)
            
                if (decoded == null) {
                    println("❌ decode fail")
                    continue
                }
                
                println("✅ DECODED: $decoded")

                result.add(decoded to quality)
            }
        }

        return result
    }

    fun decodeKodik(input: String): String? {

        if (input.startsWith("http")) return input

        for (shift in 0..25) {
            val shifted = input.map {
                when (it) {
                    in 'a'..'z' -> 'a' + (it - 'a' + shift) % 26
                    in 'A'..'Z' -> 'A' + (it - 'A' + shift) % 26
                    else -> it
                }
            }.joinToString("")

            val decoded = base64Decode(shifted)

            if (decoded != null) {
                println("TRY SHIFT $shift -> ${decoded.take(50)}")

                if (decoded.startsWith("http") || decoded.contains("m3u8")) {
                    showToast("✅ video decoded")
                    return decoded
                }
            }
        }

        showToast("❌ decode fail")
        return null
    }
    
    fun base64Decode(str: String): String? {
        return try {
            val pad = "=".repeat((4 - str.length % 4) % 4)
            String(Base64.decode(str + pad, Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    fun extractAfter(text: String, key: String): String? {
        val i = text.indexOf(key)
        if (i == -1) return null

        val start = text.indexOfAny(charArrayOf('"', '\''), i)
        val end = text.indexOf(text[start], start + 1)

        return text.substring(start + 1, end)
    }

    fun detectEndpoint(page: String): String? {
        val match = Regex("""url:atob\(["'](.*?)["']\)""").find(page)
        return match?.groupValues?.get(1)?.let { base64Decode(it) }
    }
}
