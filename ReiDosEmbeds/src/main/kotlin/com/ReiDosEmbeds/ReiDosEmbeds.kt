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
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

// Classe de dados temporária para ajudar na ordenação da agenda por cronologia
private data class TempAgendaEvent(
    val title: String,
    val url: String,
    val posterUrl: String,
    val isLive: Boolean,
    val timeStr: String,
    val sortKey: String,
    val statusBadge: String
)

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

    companion object {
        private var cachedChannelCategories: List<HomePageList>? = null
        private val channelsCacheMap = ConcurrentHashMap<String, Pair<String, String>>()
        private var lastChannelsFetch: Long = 0L
        private const val CHANNELS_CACHE_MS = 24 * 60 * 60 * 1000L // 24 horas

        private var cachedAgenda: HomePageList? = null
        private var lastAgendaFetch: Long = 0L
        private const val AGENDA_CACHE_MS = 15 * 60 * 1000L // 15 minutos
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()
        val currentTime = System.currentTimeMillis()

        // ==========================================
        // 1. LÓGICA DA AGENDA (Intacta com Horários)
        // ==========================================
        try {
            if (cachedAgenda == null || (currentTime - lastAgendaFetch) > AGENDA_CACHE_MS) {
                val tempAgendaEvents = mutableListOf<TempAgendaEvent>()
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
                            val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                            val mainUrl = card.select("div.flex.gap-2 a[href^=http]").firstOrNull()?.attr("href") ?: ""
                            
                            var eventDate = ""
                            var eventTime = ""
                            
                            val timeElements = card.select("span.inline-flex.items-center")
                            for (el in timeElements) {
                                val text = el.text().trim()
                                
                                val timeMatch = Regex("\\b\\d{2}:\\d{2}\\b").find(text)
                                if (timeMatch != null) {
                                    eventTime = timeMatch.value
                                }
                                
                                val dateMatch = Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b").find(text)
                                if (dateMatch != null) {
                                    val parts = dateMatch.value.split("/")
                                    if (parts.size == 3) {
                                        eventDate = "${parts[2]}${parts[1]}${parts[0]}" 
                                    }
                                }
                            }
                            
                            val sortKey = if (eventDate.isNotEmpty() && eventTime.isNotEmpty()) {
                                "${eventDate}${eventTime}"
                            } else if (eventTime.isNotEmpty()) {
                                "99999999${eventTime}"
                            } else {
                                "9999999999:99"
                            }
                            
                            val isLive = statusBadge.equals("AO VIVO", ignoreCase = true)
                            
                            if (mainUrl.isNotEmpty()) {
                                tempAgendaEvents.add(TempAgendaEvent(rawTitle, mainUrl, posterUrl, isLive, eventTime, sortKey, statusBadge))
                            }
                        }
                    }
                    
                    if (!foundValidEventOnPage) {
                        keepFetching = false
                    } else {
                        currentPage++
                    }
                }

                if (tempAgendaEvents.isNotEmpty()) {
                    tempAgendaEvents.sortWith(compareBy(
                        { !it.isLive },
                        { it.sortKey }
                    ))
                    
                    val agendaEvents = tempAgendaEvents.map { event ->
                        val prefix = if (event.timeStr.isNotEmpty()) "[${event.statusBadge} - ${event.timeStr}]" else "[${event.statusBadge}]"
                        val fullTitle = "$prefix ${event.title}"
                        
                        newLiveSearchResponse(fullTitle, event.url, TvType.Live) {
                            this.posterUrl = event.posterUrl
                        }
                    }

                    cachedAgenda = HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true)
                } else {
                    cachedAgenda = null
                }
                
                lastAgendaFetch = currentTime
            }
        } catch (e: Exception) {}

        cachedAgenda?.let { homeCategories.add(it) }

        // ==========================================
        // 2. LÓGICA DOS CANAIS (Tradutor Local + Quebra de Cache Envenenado)
        // ==========================================
        try {
            if (cachedChannelCategories == null || (currentTime - lastChannelsFetch) > CHANNELS_CACHE_MS) {
                val categoriesList = mutableListOf<HomePageList>()
                
                // O parâmetro ?v=2 engana o celular e força ele a ignorar o erro que estava salvo
                val categoriesResponse = app.get("$apiUrl/channels/categories?v=2", headers = defaultHeaders, cacheTime = 1440).text
                val categoriesJson = JSONObject(categoriesResponse)
                
                // Trava de segurança para não salvar erro novamente se o site cair no exato instante do click
                if (!categoriesJson.has("data")) throw Exception("O site retornou um formato inesperado ao invés das categorias.")
                
                val categoriesArray = categoriesJson.getJSONArray("data")
                
                val categoryMap = mutableMapOf<String, String>()
                for (i in 0 until categoriesArray.length()) {
                    val cat = categoriesArray.getJSONObject(i)
                    val id = cat.getString("id")
                    val name = cat.getString("name")
                    categoryMap[id] = name
                    categoryMap[id.lowercase()] = name 
                }

                // O parâmetro ?v=2 aqui faz a mesma quebra de cache para a lista global
                val allChannelsResponse = app.get("$apiUrl/channels?v=2", headers = defaultHeaders, cacheTime = 1440).text
                val allChannelsJson = JSONObject(allChannelsResponse)
                
                if (!allChannelsJson.has("data")) throw Exception("O site retornou um formato inesperado ao invés dos canais.")
                
                val allChannelsArray = allChannelsJson.getJSONArray("data")
                
                val allChannelsList = mutableListOf<SearchResponse>()
                val groupedChannels = mutableMapOf<String, MutableList<SearchResponse>>()

                channelsCacheMap.clear()

                for (i in 0 until allChannelsArray.length()) {
                    val channel = allChannelsArray.getJSONObject(i)
                    val name = channel.getString("name")
                    val slug = channel.getString("id")
                    var posterUrl = channel.optString("logo_url", "")
                    if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                    
                    channelsCacheMap[slug] = Pair(name, posterUrl)
                    val channelUrl = "https://reidosembeds.com/canal/$slug"
                    
                    val searchResponse = newLiveSearchResponse(name, channelUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                    allChannelsList.add(searchResponse)
                    
                    val categoriesOfChannel = mutableListOf<String>()
                    val catObj = channel.opt("category")
                    if (catObj is JSONArray) {
                        for (k in 0 until catObj.length()) {
                            categoriesOfChannel.add(catObj.getString(k).trim())
                        }
                    } else if (catObj is String && catObj.trim().isNotEmpty()) {
                        categoriesOfChannel.add(catObj.trim())
                    }
                    
                    if (categoriesOfChannel.isEmpty()) {
                        categoriesOfChannel.add("Outros")
                    }
                    
                    for (rawCat in categoriesOfChannel) {
                        val beautifulName = categoryMap[rawCat] ?: categoryMap[rawCat.lowercase()] ?: if (rawCat == "Outros") "Outros" else rawCat.replaceFirstChar { it.uppercase() }
                        
                        if (!groupedChannels.containsKey(beautifulName)) {
                            groupedChannels[beautifulName] = mutableListOf()
                        }
                        groupedChannels[beautifulName]?.add(searchResponse)
                    }
                }

                if (allChannelsList.isNotEmpty()) {
                    categoriesList.add(HomePageList("Todos", allChannelsList, isHorizontalImages = true))
                }

                for (i in 0 until categoriesArray.length()) {
                    val beautifulName = categoriesArray.getJSONObject(i).getString("name")
                    val channels = groupedChannels[beautifulName]
                    if (!channels.isNullOrEmpty()) {
                        categoriesList.add(HomePageList(beautifulName, channels, isHorizontalImages = true))
                        groupedChannels.remove(beautifulName)
                    }
                }

                for ((catName, channels) in groupedChannels) {
                    if (channels.isNotEmpty()) {
                        categoriesList.add(HomePageList(catName, channels, isHorizontalImages = true))
                    }
                }

                if (categoriesList.isNotEmpty()) {
                    cachedChannelCategories = categoriesList
                    lastChannelsFetch = currentTime
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.toString().take(150) 
            val errorItem = newLiveSearchResponse(errorMsg, "https://reidosembeds.com", TvType.Live) {
                this.posterUrl = "https://via.placeholder.com/300x450.png?text=ERRO+API"
            }
            homeCategories.add(HomePageList("🚨 DEBUG: ERRO NOS CANAIS", listOf(errorItem), isHorizontalImages = true))
        }

        cachedChannelCategories?.let { homeCategories.addAll(it) }

        return newHomePageResponse(homeCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
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

        val slug = url.substringAfterLast("/")
        
        var title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        var posterUrl = ""
        var embedUrl = url
        
        val cachedData = channelsCacheMap[slug]
        if (cachedData != null) {
            title = cachedData.first
            posterUrl = cachedData.second
        }

        try {
            // Utilizamos v=2 aqui também para nos aproveitarmos do cache correto da página inicial!
            val allChannelsResponse = app.get("$apiUrl/channels?v=2", headers = defaultHeaders, cacheTime = 1440).text
            val channelsArray = JSONObject(allChannelsResponse).getJSONArray("data")
            for (i in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(i)
                if (channel.getString("id") == slug) {
                    embedUrl = channel.getString("embed_url")
                    if (posterUrl.isEmpty()) {
                        posterUrl = channel.optString("logo_url", "")
                        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                    }
                    break
                }
            }
        } catch (e: Exception) {}
        
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
                var posterUrl = channel.optString("logo_url", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                val channelUrl = "https://reidosembeds.com/canal/$slug"
                
                results.add(newLiveSearchResponse(name, channelUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                })
            }
            
            val eventsArray = data.getJSONArray("events")
            for (i in 0 until eventsArray.length()) {
                val event = eventsArray.getJSONObject(i)
                val title = event.getString("title")
                var posterUrl = event.optString("poster", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                val embeds = event.getJSONArray("embeds")
                if (embeds.length() > 0) {
                    val embedUrl = embeds.getJSONObject(0).getString("embed_url") 
                    results.add(newLiveSearchResponse(title, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    })
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
                            try {
                                val optUrl = choice.attr("data-player-url")
                                val optName = choice.select(".block.truncate").text().trim()
                                if (optUrl.isNotEmpty()) {
                                    resolvePlayer(optUrl, data, optName, subtitleCallback, callback)
                                }
                            } catch (e: Exception) {} 
                        }
                    }.awaitAll()
                }
                return true
            } else {
                val mainIframe = doc.select("iframe#event-player-frame").attr("src")
                if (mainIframe.isNotEmpty()) {
                    resolvePlayer(mainIframe, data, "Principal", subtitleCallback, callback)
                    return true
                }
            }
        }

        val slug = data.substringAfterLast("/")
        try {
            // Buscando os embeds no cache recém limpado usando o v=2
            val response = app.get("$apiUrl/channels?v=2", headers = defaultHeaders, cacheTime = 1440).text
            val channelsArray = JSONObject(response).getJSONArray("data")
            for (i in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(i)
                if (channel.getString("id") == slug) {
                    val embedUrl = channel.getString("embed_url")
                    resolvePlayer(embedUrl, embedUrl, name, subtitleCallback, callback)
                    return true
                }
            }
        } catch (e: Exception) {}

        resolvePlayer(data, data, name, subtitleCallback, callback)
        return true
    }

    private suspend fun resolvePlayer(
        startUrl: String, 
        initialReferer: String, 
        sourceName: String, 
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = startUrl
        var referer = initialReferer

        for (i in 0..2) {
            try {
                if (currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be") || currentUrl.contains("twitch.tv")) {
                    loadExtractor(currentUrl, subtitleCallback, callback)
                    return
                }

                val reqHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Referer" to referer
                )

                val currentHtml = app.get(currentUrl, headers = reqHeaders).text
                
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
                
                var iframeMatch = Regex("""<iframe[^>]*src="([^"]*(?:rde\.lat|play|embed|youtube|twitch)[^"]*)"[^>]*>""").find(currentHtml)
                if (iframeMatch == null) {
                    iframeMatch = Regex("""<iframe[^>]*src="([^"]+)"[^>]*>""").find(currentHtml)
                }
                
                if (iframeMatch != null) {
                    var nextUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                    
                    if (nextUrl.contains("youtube.com") || nextUrl.contains("youtu.be") || nextUrl.contains("twitch.tv")) {
                        loadExtractor(nextUrl, subtitleCallback, callback)
                        return
                    }
                    
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