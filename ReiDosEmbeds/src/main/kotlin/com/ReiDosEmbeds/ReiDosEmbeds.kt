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
    private val blockedCategories = listOf("Adulto", "adulto", "ADULTO")

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
        // 1. LÓGICA DA AGENDA (Cache Nativo de 15 Minutos)
        // ==========================================
        try {
            // cacheTime = 15 salva a resposta no disco do celular por 15 minutos
            val agendaDoc = app.get("https://reidosembeds.com/agenda", headers = defaultHeaders, cacheTime = 15).document
            val agendaEvents = mutableListOf<SearchResponse>()
            val eventCards = agendaDoc.select("article[data-event-card]")
            
            for (card in eventCards) {
                val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                
                if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
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

            if (agendaEvents.isNotEmpty()) {
                agendaEvents.sortBy { if (it.name.contains("AO VIVO", ignoreCase = true)) 0 else 1 }
                homeCategories.add(HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true))
            }
        } catch (e: Exception) {}

        // ==========================================
        // 2. LÓGICA DOS CANAIS (Cache Nativo de 24 Horas)
        // ==========================================
        try {
            // cacheTime = 1440 salva a resposta no disco do celular por 24 horas!
            val categoriesResponse = app.get("$apiUrl/channels/categories", headers = defaultHeaders, cacheTime = 1440).text
            val categoriesJson = JSONObject(categoriesResponse)
            val categoriesArray = categoriesJson.getJSONArray("data")
            
            val validCategoryNames = mutableListOf<String>()
            
            for (i in 0 until categoriesArray.length()) {
                val catName = categoriesArray.getJSONObject(i).getString("name")
                if (!blockedCategories.contains(catName)) {
                    validCategoryNames.add(catName)
                }
            }

            // Lê do disco se estiver no prazo de 24h
            val allChannelsResponse = app.get("$apiUrl/channels", headers = defaultHeaders, cacheTime = 1440).text
            val allChannelsJson = JSONObject(allChannelsResponse)
            val allChannelsArray = allChannelsJson.getJSONArray("data")
            
            val allChannelsList = mutableListOf<SearchResponse>()
            val groupedChannels = mutableMapOf<String, MutableList<SearchResponse>>()

            for (i in 0 until allChannelsArray.length()) {
                val channel = allChannelsArray.getJSONObject(i)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                val catName = channel.optString("category", "Outros") 
                
                if (blockedCategories.contains(catName)) continue
                
                var posterUrl = channel.optString("logo_url", "")
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                // FIX: Transformar o slug em uma URL válida para o Cloudstream não bugar a interface
                val channelUrl = "https://reidosembeds.com/canal/$slug"
                
                val searchResponse = newLiveSearchResponse(name, channelUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
                
                allChannelsList.add(searchResponse)
                
                if (!groupedChannels.containsKey(catName)) {
                    groupedChannels[catName] = mutableListOf()
                }
                groupedChannels[catName]?.add(searchResponse)
            }

            if (allChannelsList.isNotEmpty()) {
                homeCategories.add(HomePageList("Todos", allChannelsList, isHorizontalImages = true))
            }
            
            for (catName in validCategoryNames) {
                val channels = groupedChannels[catName]
                if (channels != null && channels.isNotEmpty()) {
                    homeCategories.add(HomePageList(catName, channels, isHorizontalImages = true))
                }
            }
            
            for ((catName, channels) in groupedChannels) {
                if (!validCategoryNames.contains(catName) && channels.isNotEmpty()) {
                    homeCategories.add(HomePageList(catName, channels, isHorizontalImages = true))
                }
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

        // Se for um canal normal
        val slug = url.substringAfterLast("/")
        // Isso lê o arquivo JSON direto do cache de disco, sem bater no servidor (0 milissegundos)
        val allChannelsResponse = app.get("$apiUrl/channels", headers = defaultHeaders, cacheTime = 1440).text
        val channelsArray = JSONObject(allChannelsResponse).getJSONArray("data")
        
        var title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        var embedUrl = url
        var posterUrl = ""
        
        // Busca os detalhes do canal usando o slug
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
        
        // Retornamos embedUrl no parâmetro 'dataUrl' para o loadLinks conseguir extrair o vídeo
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
                val category = channel.optString("category", "")
                
                if (blockedCategories.contains(category)) continue
                
                var posterUrl = logoUrl
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                // FIX: Mesma correção da home para a pesquisa
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