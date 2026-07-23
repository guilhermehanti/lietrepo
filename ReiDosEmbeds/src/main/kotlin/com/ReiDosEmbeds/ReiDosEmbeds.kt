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

// Classe de dados temporária para ajudar na ordenação da agenda
private data class TempAgendaEvent(
    val title: String,
    val url: String,
    val posterUrl: String,
    val isLive: Boolean,
    val isBrazilian: Boolean
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

    private val brazilianKeywords = listOf(
        "brasileir", "série a", "série b", "série c", "série d", "copa do brasil", "paulistão", "carioca", 
        "mineiro", "gaúcho", "copa do nordeste", "copa verde", "baiano", "pernambucano", "cearense",
        "flamengo", "corinthians", "palmeiras", "são paulo", "vasco", "cruzeiro",
        "grêmio", "internacional", "atlético-mg", "fluminense", "botafogo", "santos",
        "bahia", "vitória", "fortaleza", "ceará", "sport", "athletico", "bragantino", "juventude", "criciúma", "atlético-go", "cuiabá",
        "coritiba", "goiás", "américa-mg", "guarani", "vila nova", "ponte preta", "novorizontino",
        "crb", "avaí", "chapecoense", "ituano", "operário", "mirassol", "paysandu", "amazonas", "botafogo-sp",
        "náutico", "figueirense", "csa", "sampaio corrêa", "londrina", "tombense", "abc", "volta redonda",
        "confiança", "ferroviário", "ypiranga", "são bernardo", "floresta", "botafogo-pb", "caxias", "remo", 
        "santa cruz", "paraná", "joinville", "campinense", "treze", "brasil de pelotas"
    )

    companion object {
        // Cache em memória para evitar travamentos de UI
        private var cachedChannelCategories: List<HomePageList>? = null
        private val channelsCacheMap = ConcurrentHashMap<String, Pair<String, String>>()
        private var lastChannelsFetch: Long = 0L
        private const val CHANNELS_CACHE_MS = 24 * 60 * 60 * 1000L // 24 horas

        // Cache em memória da agenda
        private var cachedAgenda: HomePageList? = null
        private var lastAgendaFetch: Long = 0L
        private const val AGENDA_CACHE_MS = 15 * 60 * 1000L // 15 minutos
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()
        val currentTime = System.currentTimeMillis()

        // ==========================================
        // 1. LÓGICA DA AGENDA (Paginação Dinâmica e Cache de 15 Min)
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
                            val league = card.select("span.text-zinc-400").text().trim() 
                            val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                            val mainUrl = card.select("div.flex.gap-2 a[href^=http]").firstOrNull()?.attr("href") ?: ""
                            
                            val title = "[$statusBadge] $rawTitle"
                            val searchString = "$title - $league"
                            
                            val isLive = statusBadge.equals("AO VIVO", ignoreCase = true)
                            val isBrazilian = brazilianKeywords.any { kw -> searchString.contains(kw, ignoreCase = true) }
                            
                            if (mainUrl.isNotEmpty()) {
                                tempAgendaEvents.add(TempAgendaEvent(title, mainUrl, posterUrl, isLive, isBrazilian))
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
                        { !it.isBrazilian }
                    ))
                    
                    val agendaEvents = tempAgendaEvents.map { event ->
                        newLiveSearchResponse(event.title, event.url, TvType.Live) {
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
        // 2. LÓGICA DOS CANAIS (Lotes de 4 para evitar 429)
        // ==========================================
        try {
            if (cachedChannelCategories == null || (currentTime - lastChannelsFetch) > CHANNELS_CACHE_MS) {
                val categoriesList = mutableListOf<HomePageList>()
                
                val categoriesResponse = app.get("$apiUrl/channels/categories", headers = defaultHeaders, cacheTime = 1440).text
                val categoriesArray = JSONObject(categoriesResponse).getJSONArray("data")
                
                val categoryPairs = mutableListOf<Pair<String, String>>()
                for (i in 0 until categoriesArray.length()) {
                    val cat = categoriesArray.getJSONObject(i)
                    categoryPairs.add(Pair(cat.getString("id"), cat.getString("name")))
                }

                channelsCacheMap.clear()

                // Primeiro, carregamos "Todos" os canais
                try {
                    val allChannelsResponse = app.get("$apiUrl/channels", headers = defaultHeaders, cacheTime = 1440).text
                    val allChannelsArray = JSONObject(allChannelsResponse).getJSONArray("data")
                    val allChannelsList = mutableListOf<SearchResponse>()

                    for (i in 0 until allChannelsArray.length()) {
                        val channel = allChannelsArray.getJSONObject(i)
                        val name = channel.getString("name")
                        val slug = channel.getString("id")
                        var posterUrl = channel.optString("logo_url", "")
                        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                        
                        channelsCacheMap[slug] = Pair(name, posterUrl)
                        val channelUrl = "https://reidosembeds.com/canal/$slug"
                        
                        allChannelsList.add(newLiveSearchResponse(name, channelUrl, TvType.Live) {
                            this.posterUrl = posterUrl
                        })
                    }
                    
                    if (allChannelsList.isNotEmpty()) {
                        categoriesList.add(HomePageList("Todos", allChannelsList, isHorizontalImages = true))
                    }
                } catch (e: Exception) {}

                // Agora, buscamos o conteúdo de cada categoria com precisão em lotes (chunks)
                val chunks = categoryPairs.chunked(4)
                for (chunk in chunks) {
                    coroutineScope {
                        chunk.map { (catId, catName) ->
                            async {
                                try {
                                    val channelsResponse = app.get("$apiUrl/channels?category=${catId.replace(" ", "%20")}", headers = defaultHeaders, cacheTime = 1440).text
                                    val channelsArray = JSONObject(channelsResponse).getJSONArray("data")
                                    val channels = mutableListOf<SearchResponse>()
                                    
                                    for (j in 0 until channelsArray.length()) {
                                        val channel = channelsArray.getJSONObject(j)
                                        val name = channel.getString("name")
                                        val slug = channel.getString("id")
                                        var posterUrl = channel.optString("logo_url", "")
                                        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                                        
                                        channelsCacheMap[slug] = Pair(name, posterUrl)
                                        val channelUrl = "https://reidosembeds.com/canal/$slug"
                                        
                                        channels.add(newLiveSearchResponse(name, channelUrl, TvType.Live) {
                                            this.posterUrl = posterUrl
                                        })
                                    }
                                    if (channels.isNotEmpty()) HomePageList(catName, channels, isHorizontalImages = true) else null
                                } catch (e: Exception) { null } 
                            }
                        }.awaitAll().filterNotNull().forEach { categoriesList.add(it) }
                    }
                }
                
                cachedChannelCategories = categoriesList
                lastChannelsFetch = currentTime
            }
        } catch (e: Exception) {}

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
            val allChannelsResponse = app.get("$apiUrl/channels", headers = defaultHeaders, cacheTime = 1440).text
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

        // Recuperar o link de embed original
        val slug = data.substringAfterLast("/")
        try {
            val response = app.get("$apiUrl/channels?category=all", headers = defaultHeaders, cacheTime = 1440).text
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

        // Se falhou em buscar na API completa, tenta usar a string bruta
        resolvePlayer(data, data, name, subtitleCallback, callback)
        return true
    }

    // A MÁGICA ACONTECE AQUI: Adicionado subtitleCallback nos parâmetros para podermos usar o loadExtractor nativo
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
                // 1. Delegação Nativa: O Cloudstream sabe abrir YouTube e Twitch sozinho!
                if (currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be") || currentUrl.contains("twitch.tv")) {
                    loadExtractor(currentUrl, subtitleCallback, callback)
                    return
                }

                // Proteção extra de headers na raspagem de vídeo
                val reqHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Referer" to referer
                )

                val currentHtml = app.get(currentUrl, headers = reqHeaders).text
                
                // 2. Tenta extrair o player padrão
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
                
                // 3. Procura por sub-iframes (agora englobando youtube e twitch nas regex também)
                var iframeMatch = Regex("""<iframe[^>]*src="([^"]*(?:rde\.lat|play|embed|youtube|twitch)[^"]*)"[^>]*>""").find(currentHtml)
                if (iframeMatch == null) {
                    iframeMatch = Regex("""<iframe[^>]*src="([^"]+)"[^>]*>""").find(currentHtml)
                }
                
                if (iframeMatch != null) {
                    var nextUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                    
                    // Se o iframe encontrado for do YouTube/Twitch, joga pro extrator e finaliza
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