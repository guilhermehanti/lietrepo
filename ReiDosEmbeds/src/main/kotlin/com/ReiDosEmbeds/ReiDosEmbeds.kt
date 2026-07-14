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
    private var channelsCache: MutableMap<String, Pair<String, String>> = mutableMapOf()
    private val blockedCategories = listOf("Adulto", "adulto", "ADULTO")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = mutableListOf<HomePageList>()

        // 1. Scraping da Agenda
        try {
            val agendaDoc = app.get("https://reidosembeds.com/agenda").document
            val agendaEvents = mutableListOf<SearchResponse>()
            val eventCards = agendaDoc.select("article[data-event-card]")
            
            for (card in eventCards) {
                val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                
                // Filtrar apenas Ao Vivo e Em Breve
                if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
                    val rawTitle = card.select("h3").text().trim()
                    
                    // Adiciona a TAG diretamente no título para o card do Cloudstream
                    val title = "[$statusBadge] $rawTitle"
                    val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                    
                    // Pega o link real da página do evento apontado no botão "Abrir"
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
                categories.add(HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            // Se falhar o load da agenda, ignorar para não quebrar o load dos canais
        }

        // 2. Fetch de Categorias (Canais normais)
        val categoriesResponse = app.get("$apiUrl/channels/categories").text
        val categoriesJson = JSONObject(categoriesResponse)
        val categoriesArray = categoriesJson.getJSONArray("data")
        
        for (i in 0 until categoriesArray.length()) {
            val category = categoriesArray.getJSONObject(i)
            val categoryName = category.getString("name")
            val categoryId = category.getString("id")
            
            if (blockedCategories.contains(categoryName)) continue
            
            val channelsResponse = app.get("$apiUrl/channels?category=${categoryId.replace(" ", "%20")}").text
            val channelsJson = JSONObject(channelsResponse)
            val channelsArray = channelsJson.getJSONArray("data")
            
            val channels = mutableListOf<SearchResponse>()
            
            for (j in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(j)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                val embedUrl = channel.getString("embed_url")
                val logoUrl = channel.getString("logo_url")
                
                var posterUrl = logoUrl
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                channelsCache[slug] = Pair(name, posterUrl)
                
                channels.add(
                    newLiveSearchResponse(name, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }
            
            if (channels.isNotEmpty()) {
                categories.add(HomePageList(categoryName, channels, isHorizontalImages = true))
            }
        }
        
        // 3. Fetch de Todos os Canais
        val allChannelsResponse = app.get("$apiUrl/channels").text
        val allChannelsJson = JSONObject(allChannelsResponse)
        val allChannelsArray = allChannelsJson.getJSONArray("data")
        
        val allChannels = mutableListOf<SearchResponse>()
        for (i in 0 until allChannelsArray.length()) {
            val channel = allChannelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.getString("logo_url")
            val category = channel.optString("category", "")
            
            if (blockedCategories.contains(category)) continue
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            allChannels.add(
                newLiveSearchResponse(name, embedUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
        categories.add(0, HomePageList("Todos", allChannels, isHorizontalImages = true))
        
        return newHomePageResponse(categories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        // Se for um evento da agenda
        if (url.contains("/eventos/")) {
            val doc = app.get(url).document
            val title = doc.select("h1.event-glow-title").text().trim()
            val plot = doc.select("aside.theme-card p").joinToString("\n") { it.text() }
            var posterUrl = doc.select(".event-stadium img.event-hero-bg").attr("src")
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            return newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }

        // Lógica para canais normais
        val slug = url.substringAfterLast("/")
        val channelData = channelsCache[slug]
        val title = channelData?.first ?: slug.replace("-", " ").split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.replaceFirstChar { it.uppercase() } else word
        }
        val posterUrl = channelData?.second ?: ""
        val plot = "Assista $title ao vivo!"
        
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val response = app.get("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}").text
        val json = JSONObject(response)
        val data = json.getJSONObject("data")
        
        val results = mutableListOf<SearchResponse>()
        
        val channelsArray = data.getJSONArray("channels")
        for (i in 0 until channelsArray.length()) {
            val channel = channelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.optString("logo_url", "")
            val category = channel.optString("category", "")
            
            if (blockedCategories.contains(category)) continue
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            results.add(
                newLiveSearchResponse(name, embedUrl, TvType.Live) {
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
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Se a requisição for da página individual do evento
        if (data.contains("/eventos/")) {
            val doc = app.get(data).document
            
            // Pega as opções de players na lateral da tela
            val choices = doc.select(".player-choice[data-player-url]")
            
            if (choices.isNotEmpty()) {
                coroutineScope {
                    choices.map { choice ->
                        async {
                            val optUrl = choice.attr("data-player-url")
                            val optName = choice.select(".block.truncate").text().trim()
                            if (optUrl.isNotEmpty()) {
                                // Aqui optUrl é a url do iframe (ex: v5.rde.lat/e/...). Resolvemos ele.
                                resolvePlayer(optUrl, data, optName, callback)
                            }
                        }
                    }.awaitAll()
                }
                return true
            } else {
                // Fallback: Tenta pegar o iframe exibido na tela principal caso a estrutura seja diferente
                val mainIframe = doc.select("iframe#event-player-frame").attr("src")
                if (mainIframe.isNotEmpty()) {
                    resolvePlayer(mainIframe, data, "Principal", callback)
                    return true
                }
            }
        }

        // Se for canal normal
        resolvePlayer(data, data, name, callback)
        return true
    }

    /**
     * Função recursiva que entra nos iframes de forma progressiva 
     * até encontrar o payload contendo as fontes de vídeo.
     */
    private suspend fun resolvePlayer(
        startUrl: String, 
        initialReferer: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = startUrl
        var referer = initialReferer

        // Limite de 3 "pulos" de iframe para impedir loop infinito
        for (i in 0..2) {
            try {
                val currentHtml = app.get(currentUrl, headers = mapOf("Referer" to referer)).text
                
                // 1. Verifica se já chegamos no script final (player embutido)
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
                    return // Player encontrado e processado, encerra a busca
                }
                
                // 2. Se não encontrou as fontes, procura um iframe filho para navegar
                // Prioridade 1: O iframe que contém "__play", usado nos canais
                var iframeMatch = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""").find(currentHtml)
                
                // Prioridade 2: Qualquer outro iframe genérico
                if (iframeMatch == null) {
                    iframeMatch = Regex("""<iframe[^>]*src="([^"]+)"[^>]*>""").find(currentHtml)
                }
                
                if (iframeMatch != null) {
                    var nextUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                    
                    referer = currentUrl
                    currentUrl = nextUrl
                } else {
                    // Sem fontes de video e sem iframes; quebra o ciclo
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
    }
}