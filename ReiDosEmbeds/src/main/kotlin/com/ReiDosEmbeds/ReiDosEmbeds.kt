package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://reidosembeds.com/api"

    // Headers para evitar bloqueios simples de bots
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://reidosembeds.com",
        "Referer" to "https://reidosembeds.com/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // ==========================================
        // 1. LÓGICA DA AGENDA (Paginação Dinâmica e Cache Nativo de 15 Min)
        // ==========================================
        try {
            val agendaEvents = mutableListOf<SearchResponse>()
            var currentPage = 1
            var keepFetching = true
            
            while (keepFetching && currentPage <= 10) {
                val pageUrl = "https://reidosembeds.com/agenda?status=all&page=$currentPage"
                val agendaDoc = app.get(pageUrl, headers = defaultHeaders, cacheTime = 15).document
                val eventCards = agendaDoc.select("article[data-event-card]")
                
                if (eventCards.isEmpty()) break
                
                var foundValidEventOnPage = false
                
                for (card in eventCards) {
                    val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                    
                    if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
                        foundValidEventOnPage = true
                        val rawTitle = card.select("h3").text().trim()
                        val title = "[$statusBadge] $rawTitle"
                        val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                        val mainUrl = card.select("div.flex.gap-2 a[href^=http]").firstOrNull()?.attr("href") ?: ""
                        
                        if (mainUrl.isNotEmpty()) {
                            agendaEvents.add(
                                newLiveSearchResponse(title, mainUrl, TvType.Live) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
                
                if (!foundValidEventOnPage) {
                    keepFetching = false
                } else {
                    currentPage++
                }
            }

            if (agendaEvents.isNotEmpty()) {
                agendaEvents.sortBy { if (it.name.contains("AO VIVO", ignoreCase = true)) 0 else 1 }
                homeCategories.add(HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true))
            }
        } catch (e: Exception) {}

        // ==========================================
        // 2. LÓGICA DOS CANAIS (Cache Nativo de 24 Horas)
        // ==========================================
        try {
            // Voltamos para a lógica original do site para garantir 100% de precisão nos canais
            // O cacheTime=1440 vai impedir que dê "Too Many Requests", pois as requisições ocorrerão APENAS 1 vez por dia!
            val categoriesResponse = app.get("$apiUrl/channels/categories", headers = defaultHeaders, cacheTime = 1440).text
            val categoriesJson = JSONObject(categoriesResponse)
            val categoriesArray = categoriesJson.getJSONArray("data")
            
            for (i in 0 until categoriesArray.length()) {
                val category = categoriesArray.getJSONObject(i)
                val categoryName = category.getString("name")
                val categoryId = category.getString("id")
                
                // Bloqueios de categoria foram removidos aqui para aparecerem Animes, Adulto, etc.
                
                val channelsResponse = app.get("$apiUrl/channels?category=${categoryId.replace(" ", "%20")}", headers = defaultHeaders, cacheTime = 1440).text
                val channelsJson = JSONObject(channelsResponse)
                val channelsArray = channelsJson.getJSONArray("data")
                
                val channels = mutableListOf<SearchResponse>()
                
                for (j in 0 until channelsArray.length()) {
                    val channel = channelsArray.getJSONObject(j)
                    val name = channel.getString("name")
                    val slug = channel.getString("id")
                    
                    var posterUrl = channel.optString("logo_url", "")
                    if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                    
                    val channelUrl = "https://reidosembeds.com/canal/$slug"
                    
                    channels.add(
                        newLiveSearchResponse(name, channelUrl, TvType.Live) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
                
                if (channels.isNotEmpty()) {
                    homeCategories.add(HomePageList(categoryName, channels, isHorizontalImages = true))
                }
            }

            // Categoria "Todos"
            val allChannelsResponse = app.get("$apiUrl/channels", headers = defaultHeaders, cacheTime = 1440).text
            val allChannelsJson = JSONObject(allChannelsResponse)
            val allChannelsArray = allChannelsJson.getJSONArray("data")
            
            val allChannelsList = mutableListOf<SearchResponse>()

            for (i in 0 until allChannelsArray.length()) {
                val channel = allChannelsArray.getJSONObject(i)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                
                var posterUrl = channel.optString("logo_url", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                val channelUrl = "https://reidosembeds.com/canal/$slug"
                
                allChannelsList.add(
                    newLiveSearchResponse(name, channelUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }

            if (allChannelsList.isNotEmpty()) {
                homeCategories.add(0, HomePageList("Todos", allChannelsList, isHorizontalImages = true))
            }
        } catch (e: Exception) {}

        return newHomePageResponse(homeCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        // Se for um evento da agenda
        if (url.contains("/eventos/")) {
            val doc = app.get(url, headers = defaultHeaders).document
            val title = doc.select("h1.event-glow-title").text().trim()
            val plot = doc.select("aside.theme-card p").joinToString("\n") { it.text() }
            var posterUrl = doc.select(".event-stadium img.event-hero-bg").attr("src")
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            return newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }

        // Se for um canal normal (onde a variável url guarda a url fantasiada)
        val slug = url.substringAfterLast("/")
        // Lê o arquivo JSON direto do cache de disco, sem bater no servidor (0 milissegundos)
        val allChannelsResponse = app.get("$apiUrl/channels", headers = defaultHeaders, cacheTime = 1440).text
        val channelsArray = JSONObject(allChannelsResponse).getJSONArray("data")
        
        var title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        var embedUrl = url
        var posterUrl = ""
        
        // Busca os detalhes do canal usando o slug interceptado
        for (i in 0 until channelsArray.length()) {
            val channel = channelsArray.getJSONObject(i)
            if (channel.getString("id") == slug) {
                title = channel.getString("name")
                embedUrl = channel.getString("embed_url")
                posterUrl = channel.optString("logo_url", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                break
            }
        }
        
        return newMovieLoadResponse(title, url, TvType.Live, embedUrl) {
            this.posterUrl = posterUrl
            this.plot = "Assista $title ao vivo!"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        try {
            val response = app.get("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}", headers = defaultHeaders).text
            val json = JSONObject(response)
            val data = json.getJSONObject("data")
            
            val results = mutableListOf<SearchResponse>()
            
            val channelsArray = data.getJSONArray("channels")
            for (i in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(i)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                val logoUrl = channel.optString("logo_url", "")
                
                var posterUrl = logoUrl
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                val channelUrl = "https://reidosembeds.com/canal/$slug"
                
                results.add(
                    newLiveSearchResponse(name, channelUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }
            
            val eventsArray = data.getJSONArray("events")
            for (i in 0 until eventsArray.length()) {
                val event = eventsArray.getJSONObject(i)
                val title = event.getString("title")
                val poster = event.optString("poster", "")
                val embeds = event.getJSONArray("embeds")
                
                var posterUrl = poster
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                if (embeds.length() > 0) {
                    val embedUrl = embeds.getJSONObject(0).getString("embed_url") 
                    results.add(
                        newLiveSearchResponse(title, embedUrl, TvType.Live) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
            return results
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("/eventos/")) {
            val doc = app.get(data, headers = defaultHeaders).document
            val choices = doc.select(".player-choice[data-player-url]")
            
            if (choices.isNotEmpty()) {
                coroutineScope {
                    choices.map { choice ->
                        async {
                            val optUrl = choice.attr("data-player-url")
                            val optName = choice.select(".block.truncate").text().trim()
                            if (optUrl.isNotEmpty()) {
                                resolvePlayer(optUrl, data, optName, callback)
                            }
                        }
                    }.awaitAll()
                }
                return true
            } else {
                val mainIframe = doc.select("iframe#event-player-frame").attr("src")
                if (mainIframe.isNotEmpty()) {
                    resolvePlayer(mainIframe, data, "Principal", callback)
                    return true
                }
            }
        }

        resolvePlayer(data, data, name, callback)
        return true
    }

    private suspend fun resolvePlayer(
        startUrl: String, 
        initialReferer: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = startUrl
        var referer = initialReferer

        for (i in 0..2) {
            try {
                val currentHtml = app.get(currentUrl, headers = mapOf("Referer" to referer)).text
                
                val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                val sourcesMatch = sourcesPattern.find(currentHtml)
                
                if (sourcesMatch != null) {
                    val sourcesArray = JSONArray(sourcesMatch.groupValues[1])
                    val uri = URI(currentUrl)
                    val origin = "${uri.scheme}://${uri.host}"

                    for (j in 0 until sourcesArray.length()) {
                        val source = sourcesArray.getJSONObject(j)
                        val streamUrl = source.getString("src").replace("\\/", "/")
                        val label = source.optString("label", "Source ${j + 1}")
                        val finalName = if (sourceName.isNotEmpty()) "$sourceName - $label" else label
                        
                        M3u8Helper.generateM3u8(
                            finalName,
                            streamUrl,
                            currentUrl,
                            headers = mapOf(
                                "Referer" to currentUrl,
                                "Origin" to origin,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                            )
                        ).forEach(callback)
                    }
                    return 
                }
                
                var iframeMatch = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""").find(currentHtml)
                if (iframeMatch == null) {
                    iframeMatch = Regex("""<iframe[^>]*src="([^"]+)"[^>]*>""").find(currentHtml)
                }
                
                if (iframeMatch != null) {
                    var nextUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                    
                    referer = currentUrl
                    currentUrl = nextUrl
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
    }
}