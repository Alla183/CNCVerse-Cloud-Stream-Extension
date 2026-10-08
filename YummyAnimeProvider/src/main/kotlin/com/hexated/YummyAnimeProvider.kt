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
        println("KODIK IFRAME: $iframeUrl")

        val fullUrl = when {
            iframeUrl.startsWith("//") -> "https:$iframeUrl"
            iframeUrl.startsWith("http") -> iframeUrl
            else -> "https://$iframeUrl"
        }

        try {
        // 1. Сначала открываем iframe и сохраняем cookies
            val iframeResponse = app.get(
                fullUrl,
                headers = mapOf(
                    "Referer" to "https://yani.tv/",
                    "User-Agent" to USER_AGENT,
                    "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8"
                )
            )

            val page = iframeResponse.text

            println("KODIK PAGE SIZE: ${page.length}")

        // Cookies, которые Kodik выдаёт при открытии iframe
            val cookies = iframeResponse.cookies.entries.joinToString("; ") {
                "${it.key}=${it.value}"
            }

            println("KODIK COOKIES: ${cookies.take(200)}")

        // Убираем переносы для более стабильного поиска
            val flat = page
                .replace("\n", "")
                .replace("\r", "")

        // 2. Получаем urlParams
            val rawParams = Regex(
                """\burlParams\s*=\s*'([^']+)'"""
            ).find(flat)?.groupValues?.get(1)

            if (rawParams == null) {
                println("❌ urlParams not found")
                showToast("❌ urlParams not found")
          
                return emptyList()
      
            }

      
            val params = try {
         
                JSONObject(rawParams)
    
            } catch (e: Exception) {
        
                println("❌ urlParams JSON error: ${e.message}")
         
                showToast("❌ params error")
         
                return emptyList()
     
            }

        // 3. YummyTV поддерживает и videoInfo, и vInfo
      
            val type = Regex(
         
                """\b(?:videoInfo|vInfo)\.type\s*=\s*'([^']+)'"""
      
            ).find(flat)?.groupValues?.get(1)

      
            val hash = Regex(
          
                """\b(?:videoInfo|vInfo)\.hash\s*=\s*'([^']+)'"""
      
            ).find(flat)?.groupValues?.get(1)

      
            val id = Regex(
        
                """\b(?:videoInfo|vInfo)\.id\s*=\s*'([^']+)'"""
      
            ).find(flat)?.groupValues?.get(1)

      
            println("TYPE: $type")
     
            println("HASH: $hash")
     
            println("ID: $id")

    
            if (type == null || hash == null || id == null) {
         
                println("❌ Kodik video context not found")
          
                showToast("❌ context parse fail")
         
                return emptyList()
    
            }

        // 4. Находим player_single*.js
     
            val playerSrc = Regex(
          
                """src="((?://[^"]+)?/assets/js/app\.player_single[^"]+)"""
     
            ).find(flat)?.groupValues?.get(1)

     
            if (playerSrc == null) {
         
                println("❌ player_single.js not found")
          
                showToast("❌ player js not found")
           
                return emptyList()
       
            }

       
            val urlOrigin = fullUrl.let { url ->
          
                val schemeEnd = url.indexOf("://")

          
                if (schemeEnd >= 0) {
              
                    val afterScheme = url.substring(schemeEnd + 3)
               
                    val slashIdx = afterScheme.indexOf('/')

             
                    if (slashIdx >= 0) {
                
                        url.substring(0, schemeEnd + 3 + slashIdx)
              
                    } else {
                  
                        url
              
                    }
         
                } else {
               
                    url
           
                }
       
            }

      
            val playerScriptUrl = when {
          
                playerSrc.startsWith("//") -> "https:$playerSrc"
          
                playerSrc.startsWith("/") -> "$urlOrigin$playerSrc"
           
                else -> playerSrc
    
            }

    
            println("PLAYER JS: $playerScriptUrl")

        // 5. Загружаем JS-файл
      
            val playerScript = app.get(
          
                playerScriptUrl,
         
                headers = mapOf(
              
                    "Referer" to fullUrl,
               
                    "User-Agent" to USER_AGENT,
               
                    "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8"
          
                )
      
            ).text

        // 6. Endpoint находится внутри atob("...")
      
            val endpointPath = Regex(
           
                """atob\("([A-Za-z0-9+/=]+)"\)"""
      
            ).findAll(playerScript)
          
                .mapNotNull { match ->
              
                    try {
                 
                        val decoded = base64Decode(
                    
                            match.groupValues[1]
                 
                        )

                  
                        decoded?.takeIf {
                    
                            it.startsWith("/") &&
                      
                            !it.startsWith("//") &&
                       
                            it.length <= 10
                 
                        }
               
                    } catch (_: Exception) {
                 
                        null
              
                    }
           
                }
          
                .firstOrNull()
          
            ?: "/ftor"

      
            println("KODIK ENDPOINT: $endpointPath")

     
            val origin = playerScriptUrl.substringBefore("/assets/js/")
      
            val endpointUrl = "$origin$endpointPath"

      
            println("KODIK POST URL: $endpointUrl")

        // 7. ВАЖНО:
        // ref уже URL-encoded внутри urlParams.
        // НЕ используем Uri.decode().
   
            val body = FormBody.Builder()
           
                .add("d", params.optString("d"))
          
                .add("d_sign", params.optString("d_sign"))
           
                .add("pd", params.optString("pd"))
           
                .add("pd_sign", params.optString("pd_sign"))
            
                .add("ref", params.optString("ref"))
          
                .add("ref_sign", params.optString("ref_sign"))
         
                .add("bad_user", "true")
         
                .add("cdn_is_working", "true")
         
                .add("type", type)
          
                .add("hash", hash)
          
                .add("id", id)
          
                .add("info", "{}")
          
                .build()

        // 8. POST на Kodik
    
            val response = app.post(
         
                url = endpointUrl,
          
                headers = mapOf(
             
                    "Referer" to fullUrl,
               
                    "User-Agent" to USER_AGENT,
              
                    "X-Requested-With" to "XMLHttpRequest",
             
                    "Cookie" to cookies,
               
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
         
                ),
         
                requestBody = body
      
            )

       
            val responseText = response.text

      
            println(
          
                "KODIK RESPONSE: ${responseText.take(500)}"
    
            )

     
            if (!responseText.trim().startsWith("{")) {
          
                println("❌ Kodik returned non-JSON")
           
                showToast("❌ Kodik response error")
           
                return emptyList()
      
            }

        // 9. Разбираем links
      
            val json = JSONObject(responseText)
       
            val links = json.optJSONObject("links")

       
            if (links == null) {
         
                println("❌ links object not found")
           
                showToast("❌ no links")
            
                return emptyList()
      
            }

     
            val result = mutableListOf<Pair<String, String>>()

        // Порядок качества
      
            val qualityOrder = listOf(
          
                "2160",
          
                "1440",
          
                "1080",
        
                "720",
         
                "480",
           
                "360",
           
                "240"
      
            )

     
            for (quality in qualityOrder) {
         
                val array = links.optJSONArray(quality)
                    ?: continue

          
                for (i in 0 until array.length()) {
               
                    val item = array.optJSONObject(i)
                        ?: continue

              
                    val src = item.optString("src")
                
                        .takeIf { it.isNotBlank() }
                        ?: continue

               
                    println(
                   
                        "KODIK SRC [$quality]: ${src.take(100)}"
             
                    )

               
                    val decoded = decodeKodik(src)
                        ?: continue

               
                    println(
                  
                        "KODIK DECODED [$quality]: ${decoded.take(150)}"
               
                    )

              
                    result.add(decoded to quality)
           
                }
        
            }

        // Если качество пришло нестандартное
    
            links.keys().forEach { quality ->
            
                if (quality in qualityOrder) return@forEach

          
                val array = links.optJSONArray(quality)
                    ?: return@forEach

          
                for (i in 0 until array.length()) {
              
                    val item = array.optJSONObject(i)
                        ?: continue

               
                    val src = item.optString("src")
                   
                        .takeIf { it.isNotBlank() }
                        ?: continue

              
                    val decoded = decodeKodik(src)
                        ?: continue

             
                    result.add(decoded to quality)
            
                }
    
            }

    
            println("KODIK EXTRACTED: ${result.size}")

       
            return result

  
        } catch (e: Exception) {
      
            e.printStackTrace()
       
            println("❌ KODIK EXTRACT ERROR: ${e.message}")
       
            showToast("❌ Kodik extractor error")
       
            return emptyList()
   
        }

    }


/**
 * Kodik src:
 * ROT18 -> Base64
 */

    fun decodeKodik(input: String): String? {

   
        if (
    
            input.startsWith("http://") ||
       
                input.startsWith("https://")
   
        ) {
       
            return input
  
        }

  
        return try {
        // YummyTV использует именно ROT18
       
            val rotated = input.map { c ->
           
                if (c.isLetter()) {
               
                    val shifted = c.code + 18
               
                    val limit = if (c <= 'Z') 90 else 122

              
                    if (shifted <= limit) {
                  
                        shifted.toChar()
               
                    } else {
                  
                        (shifted - 26).toChar()
               
                    }
         
                } else {
               
                    c
           
                }
     
            }.joinToString("")

        
            val decoded = base64Decode(rotated)

        
            if (
         
                decoded != null &&
        
                    (
               
                        decoded.startsWith("http://") ||
                
                            decoded.startsWith("https://") ||
                
                            decoded.contains(".m3u8")
           
                    )
     
            ) {
          
                return decoded
       
            }

       
            null

   
        } catch (e: Exception) {
      
            println("KODIK DECODE ERROR: ${e.message}")
       
            null
  
        }

    }



    fun base64Decode(str: String): String? {
   
        return try {
       
            val padded = str +
            
            "=".repeat((4 - str.length % 4) % 4)

        
            String(
            
                Base64.decode(
               
                    padded,
               
                    Base64.DEFAULT
            
                )
       
            )
  
        } catch (e: Exception) {
      
            null
   
        }

    }


/**
 * Оставляем для совместимости с остальным кодом плагина.
 */

    fun extractAfter(
   
        text: String,
   
        key: String

    ): String? {
    
        val index = text.indexOf(key)

    
        if (index == -1) {
       
            return null
  
        }

  
        val start = text.indexOfAny(
      
            charArrayOf('"', '\''),
       
            index
   
        )

  
        if (start == -1) {
        
            return null
  
        }

  
        val quote = text[start]

   
        val end = text.indexOf(
     
            quote,
      
            start + 1
 
        )

    
        if (end == -1) {
     
            return null
 
        }

  
        return text.substring(
     
            start + 1,
       
            end
    
        )

    }


/**
 * Старый вариант оставляем как fallback.
 */

    fun detectEndpoint(page: String): String? {

   
        val match = Regex(
       
            """url:atob\(["'](.*?)["']\)"""
  
        ).find(page)

   
        return match
            ?.groupValues
            ?.get(1)
            ?.let { base64Decode(it) }

    }
}
