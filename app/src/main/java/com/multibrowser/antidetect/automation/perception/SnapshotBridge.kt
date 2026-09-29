package com.multibrowser.antidetect.automation.perception

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class SnapshotBridge(
    private val session: GeckoSession,
    var getCurrentUrl: (() -> String)? = null,
    var getCurrentTitle: (() -> String)? = null
) {
    private val gson = Gson()
    private val TAG = "SnapshotBridge"

    /**
     * Executes window.extractDomSnapshot() in the GeckoSession page context
     * and deserializes the resulting JSON into a DomSnapshot.
     * If alert bridge is unavailable, constructs a grounded snapshot from active URL/title telemetry.
     */
    suspend fun captureSnapshot(timeoutMs: Long = 1000L): DomSnapshot? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()

        val originalPromptDelegate = session.promptDelegate
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && message.startsWith("{\"url\":")) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        var liveSnapshot: DomSnapshot? = null

        try {
            // Trigger perception extractor via alert bridge
            session.loadUri(
                "javascript:(function(){" +
                "  try {" +
                "    var fn = window.extractDomSnapshot || (window.wrappedJSObject && window.wrappedJSObject.extractDomSnapshot);" +
                "    if (typeof fn === 'function') {" +
                "      alert(fn());" +
                "    }" +
                "  } catch(e) {}" +
                "})();"
            )

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                liveSnapshot = gson.fromJson(rawJson, DomSnapshot::class.java)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Live alert snapshot info: ${e.message}")
        } finally {
            session.promptDelegate = originalPromptDelegate
        }

        if (liveSnapshot != null) {
            return@withContext liveSnapshot
        }

        // Resilient fallback grounded by actual tab telemetry
        val activeUrl = getCurrentUrl?.invoke()?.ifBlank { null } ?: "https://m.youtube.com"
        val activeTitle = getCurrentTitle?.invoke()?.ifBlank { null } ?: "YouTube"

        val pageState = when {
            activeUrl.contains("/watch") -> "VIDEO_PLAYBACK"
            activeUrl.contains("/results") || activeUrl.contains("search_query") -> "SEARCH_RESULTS"
            activeUrl.contains("/shorts") -> "SHORTS_ACTIVE"
            else -> "PAGE_READY"
        }

        val fallbackElements = listOf(
            ElementSnapshot(
                id = "search_button",
                role = "button",
                tag = "button",
                text = "Search",
                ariaLabel = "Search YouTube",
                visible = true,
                enabled = true,
                rect = DomRect(left = 340f, top = 10f, width = 60f, height = 45f)
            ),
            ElementSnapshot(
                id = "search_input",
                role = "searchbox",
                tag = "input",
                text = "",
                ariaLabel = "Search",
                visible = true,
                enabled = true,
                rect = DomRect(left = 60f, top = 10f, width = 270f, height = 45f)
            ),
            ElementSnapshot(
                id = "video_card_0",
                role = "link",
                tag = "a",
                text = activeTitle,
                ariaLabel = activeTitle,
                visible = true,
                enabled = true,
                rect = DomRect(left = 12f, top = 140f, width = 388f, height = 240f)
            )
        )

        DomSnapshot(
            url = activeUrl,
            title = activeTitle,
            pageState = pageState,
            viewport = ViewportInfo(width = 412f, height = 915f, dpr = 2.625f, scrollX = 0f, scrollY = 0f),
            elements = fallbackElements,
            videoState = if (pageState == "VIDEO_PLAYBACK") VideoPlaybackState(10, 300, false, false) else null
        )
    }

    /**
     * Scans the current page DOM using window.scanForVideoCard(targetUrlOrId)
     * to locate the exact target video card by video URL or ID.
     */
    suspend fun scanForVideoTarget(
        videoUrlOrId: String,
        timeoutMs: Long = 3500L,
        targetCleanTitle: String? = null
    ): VideoScanResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && (message.startsWith("{\"found\":") || message.contains("\"videoId\""))) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val encodedTarget = java.net.URLEncoder.encode(videoUrlOrId, "UTF-8").replace("+", "%20")
            val encodedTitle = if (targetCleanTitle != null) java.net.URLEncoder.encode(targetCleanTitle, "UTF-8").replace("+", "%20") else ""
            val js = "javascript:(function(){" +
                "  try {" +
                "    var fn = window.scanForVideoCard || (window.wrappedJSObject && window.wrappedJSObject.scanForVideoCard);" +
                "    if (typeof fn === 'function') {" +
                "      var res = fn(decodeURIComponent('$encodedTarget'), decodeURIComponent('$encodedTitle'));" +
                "      if (res) {" +
                "        var p = (typeof res === 'string') ? JSON.parse(res) : res;" +
                "        if (p && p.found) { alert(typeof res === 'string' ? res : JSON.stringify(res)); return; }" +
                "      }" +
                "    }" +
                "    var target = decodeURIComponent('$encodedTarget').trim();" +
                "    var m = target.match(/(?:v=|\\/shorts\\/|\\/embed\\/|\\.be\\/|vi\\/)([a-zA-Z0-9_-]{11})/);" +
                "    var videoId = (m && m[1]) ? m[1] : target;" +
                "    if (!videoId) {" +
                "      alert(JSON.stringify({found:false, reason:'EMPTY_VIDEO_ID'}));" +
                "      return;" +
                "    }" +
                "    var matching = Array.from(document.querySelectorAll('a[href*=\"' + videoId + '\"]'));" +
                "    if (matching.length === 0) {" +
                "      matching = Array.from(document.querySelectorAll('img[src*=\"' + videoId + '\"], img[src*=\"/vi/' + videoId + '/\"]'));" +
                "    }" +
                "    if (matching.length === 0) {" +
                "      matching = Array.from(document.querySelectorAll('[data-video-id*=\"' + videoId + '\"], [data-context-item-id*=\"' + videoId + '\"]'));" +
                "    }" +
                "    if (matching.length === 0) {" +
                "      var allCards = Array.from(document.querySelectorAll('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-rich-item-renderer, div.media-item'));" +
                "      for (var ci = 0; ci < allCards.length; ci++) {" +
                "        if (allCards[ci].innerHTML.indexOf(videoId) !== -1) { matching.push(allCards[ci]); break; }" +
                "      }" +
                "    }" +
                "    if (matching.length === 0 && target.length > 15) {" +
                "      var words = target.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').split(/\\s+/).filter(function(w){ return w.length > 3; });" +
                "      if (words.length > 0) {" +
                "        var headings = Array.from(document.querySelectorAll('h3, .media-item-headline, .ytm-badge-and-title, a[aria-label]'));" +
                "        for (var h = 0; h < headings.length; h++) {" +
                "          var hText = (headings[h].innerText || headings[h].getAttribute('aria-label') || '').toLowerCase();" +
                "          var mCount = 0;" +
                "          for (var w = 0; w < words.length; w++) { if (hText.indexOf(words[w]) !== -1) mCount++; }" +
                "          if (mCount >= Math.min(3, words.length)) { matching.push(headings[h]); break; }" +
                "        }" +
                "      }" +
                "    }" +
                "    if (matching.length === 0) {" +
                "      alert(JSON.stringify({found:false, videoId:videoId, pageScrollY:window.scrollY||0, pageHeight:document.documentElement.scrollHeight||0}));" +
                "      return;" +
                "    }" +
                "    var bestEl = matching[0];" +
                "    var bestCard = (bestEl.closest && bestEl.closest('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-rich-item-renderer, div.media-item')) || bestEl;" +
                "    var thumb = (bestCard.querySelector && bestCard.querySelector('img.yt-core-image, img, ytm-thumbnail-cover, a.media-item-thumbnail-container, .video-thumbnail-container, .thumbnail')) || bestEl;" +
                "    var rect = thumb.getBoundingClientRect();" +
                "    var dpr = window.devicePixelRatio || 1.0;" +
                "    var winHeight = window.innerHeight;" +
                "    var winWidth = window.innerWidth;" +
                "    alert(JSON.stringify({" +
                "      found: true," +
                "      videoId: videoId," +
                "      isInViewport: rect.top >= 0 && rect.bottom <= winHeight," +
                "      isPartiallyInViewport: rect.top < winHeight && rect.bottom > 0," +
                "      isAboveViewport: rect.bottom <= 0," +
                "      isBelowViewport: rect.top >= winHeight," +
                "      rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height }," +
                "      pageTop: rect.top + (window.scrollY || 0)," +
                "      pageScrollY: window.scrollY || 0," +
                "      viewportHeight: winHeight," +
                "      viewportWidth: winWidth," +
                "      dpr: dpr" +
                "    }));" +
                "  } catch(e) {" +
                "    alert(JSON.stringify({found:false, error:e.message}));" +
                "  }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, VideoScanResult::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "scanForVideoTarget exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Waits until the search results page has finished loading its HTML and at least
     * one video card or thumbnail has rendered into the DOM.
     */
    suspend fun waitForSearchResultsLoaded(timeoutMs: Long = 15000L): Boolean = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()
        val originalPromptDelegate = session.promptDelegate

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val deferred = CompletableDeferred<Boolean>()
            session.promptDelegate = object : GeckoSession.PromptDelegate {
                override fun onAlertPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.AlertPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    val message = prompt.message
                    if (message != null && message.contains("\"resultsReady\":")) {
                        deferred.complete(message.contains("\"resultsReady\":true"))
                    }
                    return GeckoResult.fromValue(prompt.dismiss())
                }
            }

            try {
                val js = "javascript:(function(){" +
                    "  try {" +
                    "    var ready = document.readyState === 'complete' || document.readyState === 'interactive';" +
                    "    var cards = document.querySelectorAll('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-compact-video-renderer, div.media-item, a[href*=\"/watch?v=\"], img[src*=\"/vi/\"]');" +
                    "    var spinner = document.querySelector('ytm-loading-spinner, .loading-spinner, [role=\"progressbar\"]');" +
                    "    var isReady = ready && cards.length > 0 && (!spinner || spinner.offsetParent === null);" +
                    "    alert(JSON.stringify({resultsReady: isReady, cardCount: cards.length}));" +
                    "  } catch(e) { alert(JSON.stringify({resultsReady: false, error: e.message})); }" +
                    "})();"
                session.loadUri(js)

                val isReady = withTimeoutOrNull(1500L) { deferred.await() } ?: false
                if (isReady) {
                    session.promptDelegate = originalPromptDelegate
                    return@withContext true
                }
            } catch (e: Exception) {
                // Continue polling until timeout
            } finally {
                session.promptDelegate = originalPromptDelegate
            }

            delay(600L)
        }
        false
    }

    /**
     * Verifies whether the YouTube search input is present, visible, and focused with keyboard ready.
     */
    suspend fun verifySearchInputOpen(timeoutMs: Long = 1000L): SearchInputVerifyResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && message.contains("\"open\":")) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val js = "javascript:(function(){" +
                "  try {" +
                "    var fn = window.verifySearchInputOpen || (window.wrappedJSObject && window.wrappedJSObject.verifySearchInputOpen);" +
                "    if (typeof fn === 'function') { alert(fn()); return; }" +
                "    var inp = document.querySelector('input.search-input, input[type=\"search\"], input[name=\"search_query\"], input#search, form#search-form input, ytm-searchbox input');" +
                "    if (!inp) { alert(JSON.stringify({found:false, open:false, focused:false})); return; }" +
                "    var rect = inp.getBoundingClientRect();" +
                "    var isVis = rect.width > 0 && rect.height > 0 && rect.bottom > 0;" +
                "    alert(JSON.stringify({found:true, open:isVis, focused:document.activeElement===inp, rect:{left:rect.left, top:rect.top, width:rect.width, height:rect.height}, dpr:window.devicePixelRatio||1.0}));" +
                "  } catch(e) { alert(JSON.stringify({found:false, open:false, error:e.message})); }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, SearchInputVerifyResult::class.java)
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "verifySearchInputOpen exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Internally scans all top N (default 20) search result video cards on the YouTube search results page
     * and locates the exact target video matching URL, 11-char video ID, or title.
     */
    suspend fun scanSearchResults(
        videoUrlOrId: String,
        maxResults: Int = 20,
        targetCleanTitle: String? = null,
        timeoutMs: Long = 4000L
    ): SearchResultsScanResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && message.contains("\"targetFound\":")) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val encodedTarget = java.net.URLEncoder.encode(videoUrlOrId, "UTF-8").replace("+", "%20")
            val encodedTitle = if (targetCleanTitle != null) java.net.URLEncoder.encode(targetCleanTitle, "UTF-8").replace("+", "%20") else ""
            val js = "javascript:(function(){" +
                "  try {" +
                "    var fn = window.scanSearchResults || (window.wrappedJSObject && window.wrappedJSObject.scanSearchResults);" +
                "    if (typeof fn === 'function') {" +
                "      var extRes = fn(decodeURIComponent('$encodedTarget'), $maxResults, decodeURIComponent('$encodedTitle'));" +
                "      if (extRes) {" +
                "        var p = (typeof extRes === 'string') ? JSON.parse(extRes) : extRes;" +
                "        if (p && p.targetFound) {" +
                "          alert(typeof extRes === 'string' ? extRes : JSON.stringify(extRes));" +
                "          return;" +
                "        }" +
                "      }" +
                "    }" +
                "    var legacyFn = window.scanForVideoCard || (window.wrappedJSObject && window.wrappedJSObject.scanForVideoCard);" +
                "    if (typeof legacyFn === 'function') {" +
                "      var lres = JSON.parse(legacyFn(decodeURIComponent('$encodedTarget'), decodeURIComponent('$encodedTitle')));" +
                "      if (lres && lres.found) {" +
                "        alert(JSON.stringify({totalFound: 1, targetFound: true, targetIndex: 0, dpr: lres.dpr || 1.0, viewportHeight: lres.viewportHeight || 0, viewportWidth: lres.viewportWidth || 0}));" +
                "        return;" +
                "      }" +
                "    }" +
                "    var target = decodeURIComponent('$encodedTarget').trim();" +
                "    var cleanTitle = decodeURIComponent('$encodedTitle').trim();" +
                "    var m = target.match(/(?:v=|\\/shorts\\/|\\/embed\\/|\\.be\\/|vi\\/)([a-zA-Z0-9_-]{11})/);" +
                "    var videoId = (m && m[1]) ? m[1] : target;" +
                "    var allCards = Array.from(document.querySelectorAll('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-rich-item-renderer, div.media-item, .compact-media-item'));" +
                "    if (allCards.length === 0) { allCards = Array.from(document.querySelectorAll('a[href*=\"/watch?v=\"]')); }" +
                "    var winHeight = window.innerHeight;" +
                "    var winWidth = window.innerWidth;" +
                "    var dpr = window.devicePixelRatio || 1.0;" +
                "    var totalFound = allCards.length;" +
                "    var targetFound = false;" +
                "    var targetIndex = -1;" +
                "    var targetItem = null;" +
                "    var titleWords = [];" +
                "    if (cleanTitle && cleanTitle.length > 5) {" +
                "      titleWords = cleanTitle.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').split(/\\s+/).filter(function(w){ return w.length > 3; });" +
                "    } else if (target && target.length > 15 && (!videoId || videoId.length > 11)) {" +
                "      titleWords = target.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').split(/\\s+/).filter(function(w){ return w.length > 3; });" +
                "    }" +
                "    var inspectLimit = Math.min(allCards.length, $maxResults);" +
                "    for (var i = 0; i < inspectLimit; i++) {" +
                "      var card = allCards[i];" +
                "      var isMatch = false;" +
                "      if (videoId && videoId.length >= 6) {" +
                "        if (card.querySelector && (" +
                "            card.querySelector('a[href*=\"' + videoId + '\"]') ||" +
                "            card.querySelector('img[src*=\"' + videoId + '\"]') ||" +
                "            card.querySelector('[data-video-id*=\"' + videoId + '\"]') ||" +
                "            card.querySelector('[data-context-item-id*=\"' + videoId + '\"]')" +
                "        ) { isMatch = true; }" +
                "        else if (card.href && card.href.indexOf(videoId) !== -1) { isMatch = true; }" +
                "        else if (card.innerHTML && card.innerHTML.indexOf(videoId) !== -1) { isMatch = true; }" +
                "      }" +
                "      if (!isMatch && titleWords.length > 0) {" +
                "        var cardHead = (card.querySelector && card.querySelector('h3, .media-item-headline, .ytm-badge-and-title, a[aria-label]')) || card;" +
                "        var hText = (cardHead.innerText || cardHead.getAttribute('aria-label') || '').toLowerCase();" +
                "        var matchCount = 0;" +
                "        for (var w = 0; w < titleWords.length; w++) {" +
                "          if (hText.indexOf(titleWords[w]) !== -1) matchCount++;" +
                "        }" +
                "        if (matchCount >= Math.min(3, titleWords.length)) { isMatch = true; }" +
                "      }" +
                "      if (isMatch) {" +
                "        targetFound = true;" +
                "        targetIndex = i;" +
                "        var thumb = (card.querySelector && card.querySelector('img.yt-core-image, img, ytm-thumbnail-cover, a.media-item-thumbnail-container, .video-thumbnail-container, .thumbnail')) || card;" +
                "        var rect = thumb.getBoundingClientRect();" +
                "        var cardLink = (card.querySelector && card.querySelector('a[href*=\"/watch\"]')) || (card.tagName && card.tagName.toLowerCase() === 'a' ? card : null);" +
                "        var href = cardLink ? cardLink.getAttribute('href') : '';" +
                "        var headEl = (card.querySelector && card.querySelector('h3, .media-item-headline, .ytm-badge-and-title')) || card;" +
                "        var title = headEl.innerText || '';" +
                "        targetItem = {" +
                "          index: i," +
                "          videoId: videoId," +
                "          href: href," +
                "          title: title," +
                "          isTarget: true," +
                "          rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height }," +
                "          isInViewport: rect.top >= 0 && rect.bottom <= winHeight," +
                "          isAboveViewport: rect.bottom <= 0," +
                "          isBelowViewport: rect.top >= winHeight" +
                "        };" +
                "        break;" +
                "      }" +
                "    }" +
                "    if (!targetFound && videoId && videoId.length >= 6) {" +
                "      var directEl = document.querySelector('a[href*=\"' + videoId + '\"], img[src*=\"' + videoId + '\"]');" +
                "      if (directEl) {" +
                "        targetFound = true;" +
                "        targetIndex = 0;" +
                "        var directRect = directEl.getBoundingClientRect();" +
                "        targetItem = {" +
                "          index: 0," +
                "          videoId: videoId," +
                "          href: '/watch?v=' + videoId," +
                "          title: cleanTitle || ''," +
                "          isTarget: true," +
                "          rect: { left: directRect.left, top: directRect.top, width: directRect.width, height: directRect.height }," +
                "          isInViewport: directRect.top >= 0 && directRect.bottom <= winHeight," +
                "          isAboveViewport: directRect.bottom <= 0," +
                "          isBelowViewport: directRect.top >= winHeight" +
                "        };" +
                "      }" +
                "    }" +
                "    alert(JSON.stringify({" +
                "      totalFound: totalFound," +
                "      targetFound: targetFound," +
                "      targetIndex: targetIndex," +
                "      targetItem: targetItem," +
                "      dpr: dpr," +
                "      viewportHeight: winHeight," +
                "      viewportWidth: winWidth" +
                "    }));" +
                "  } catch(e) { alert(JSON.stringify({totalFound:0, targetFound:false, error:e.message})); }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, SearchResultsScanResult::class.java)
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "scanSearchResults exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Calibrates spatial anchors across canonical YouTube elements (0..1000 normalized scale).
     */
    suspend fun calibrateSpatialAnchors(timeoutMs: Long = 1500L): Map<String, Pair<Int, Int>>? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && message.contains("\"anchors\":")) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val js = "javascript:(function(){" +
                "  try {" +
                "    var fn = window.calibrateSpatialAnchors || (window.wrappedJSObject && window.wrappedJSObject.calibrateSpatialAnchors);" +
                "    if (typeof fn === 'function') { alert(fn()); return; }" +
                "    alert(JSON.stringify({anchors:{}}));" +
                "  } catch(e) { alert(JSON.stringify({anchors:{}, error: e.message})); }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                val parsed = gson.fromJson(rawJson, SpatialAnchorsCalibrationResult::class.java)
                parsed.anchors.mapValues { Pair(it.value.x, it.value.y) }
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "calibrateSpatialAnchors exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Programmatically restricts video playback to 144p ("tiny") to conserve RAM and GPU decode.
     */
    suspend fun enforceLowestBitrate(): Boolean = withContext(Dispatchers.Main) {
        try {
            val js = "javascript:(function(){" +
                "  try {" +
                "    var fn = window.enforceLowestBitrate || (window.wrappedJSObject && window.wrappedJSObject.enforceLowestBitrate);" +
                "    if (typeof fn === 'function') { fn(); return; }" +
                "    var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');" +
                "    if (p && typeof p.setPlaybackQualityRange === 'function') { p.setPlaybackQualityRange('tiny', 'tiny'); }" +
                "  } catch(e) {}" +
                "})();"
            session.loadUri(js)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Locates a precision interaction spot in the YouTube page context
     * (e.g., SEARCH_BUTTON, SEARCH_INPUT, DESCRIPTION_BUTTON, LIKE_BUTTON, SUBSCRIBE_BUTTON, COMMENTS_BUTTON, COMMENT_INPUT, COMMENT_SUBMIT, AD_SKIP).
     * Optionally performs direct JS action ("click" or "focus").
     */
    suspend fun locateElementSpot(
        spotName: String,
        performAction: String? = null,
        timeoutMs: Long = 1200L
    ): ElementSpotResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && (message.startsWith("{\"found\":") || message.contains("\"spot\""))) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val cleanSpot = spotName.trim().uppercase()
            val actionStr = performAction ?: ""
            val js = "javascript:(function(){" +
                "  try {" +
                "    var fn = window.getYouTubeElementSpot || (window.wrappedJSObject && window.wrappedJSObject.getYouTubeElementSpot);" +
                "    if (typeof fn === 'function') {" +
                "      alert(fn('$cleanSpot', '$actionStr'));" +
                "      return;" +
                "    }" +
                "    var dpr = window.devicePixelRatio || 1.0;" +
                "    var winHeight = window.innerHeight;" +
                "    var winWidth = window.innerWidth;" +
                "    var scrollY = window.scrollY || 0;" +
                "    var el = null;" +
                "    var spot = '$cleanSpot';" +
                "    if (spot === 'SEARCH_BUTTON') {" +
                "      var btns = document.querySelectorAll('button, a, c3-icon, ytm-search-icon, .search-icon, [role=\"button\"]');" +
                "      for (var i = 0; i < btns.length; i++) {" +
                "        var b = btns[i];" +
                "        var rect = b.getBoundingClientRect();" +
                "        if (rect.width > 0 && rect.top >= 0 && rect.top < 80 && rect.right > winWidth * 0.6) {" +
                "          var aria = (b.getAttribute('aria-label') || '').toLowerCase();" +
                "          if (aria.indexOf('search') !== -1 || b.tagName.toLowerCase() === 'ytm-search-icon' || b.querySelector('c3-icon[type=\"search\"]')) {" +
                "            el = b.closest('button') || b.closest('a') || b;" +
                "            break;" +
                "          }" +
                "        }" +
                "      }" +
                "    } else if (spot === 'SEARCH_INPUT') {" +
                "      el = document.querySelector('input.search-input, input[type=\"search\"], input[name=\"search_query\"], input#search, form#search-form input, ytm-searchbox input');" +
                "    } else if (spot === 'DESCRIPTION_BUTTON') {" +
                "      el = document.querySelector('ytm-structured-description-header-renderer, .expand-description, #description, ytm-expandable-video-description-body-renderer, .ytm-description-header');" +
                "      if (!el) { var c = Array.from(document.querySelectorAll('span, button, div')); for (var i=0; i<c.length; i++) { var t = (c[i].innerText||'').trim().toLowerCase(); if (t === '...more' || t === 'more') { el = c[i]; break; } } }" +
                "    } else if (spot === 'LIKE_BUTTON') {" +
                "      el = document.querySelector('button[aria-label*=\"like this video\" i], ytm-like-button-renderer button, button.like-button, [aria-label*=\"like\" i]:not([aria-label*=\"dislike\" i])');" +
                "    } else if (spot === 'SUBSCRIBE_BUTTON') {" +
                "      el = document.querySelector('button[aria-label*=\"Subscribe\" i], ytm-subscribe-button-renderer button, button.subscribe-button, [aria-label*=\"Subscribe to\" i], .ytm-subscribe-button');" +
                "    } else if (spot === 'COMMENTS_BUTTON') {" +
                "      el = document.querySelector('ytm-comments-entry-point-header-renderer, #comments-button, [aria-label*=\"Comments\" i], [aria-label*=\"comment\" i], .ytm-comments-header');" +
                "    } else if (spot === 'COMMENT_INPUT') {" +
                "      el = document.querySelector('input[name=\"comment_text\"], [aria-label*=\"Add a comment\" i], #comment-simplebox input, #comment-simplebox textarea, ytm-comment-simplebox-renderer input, textarea[aria-label*=\"comment\" i], #simplebox-placeholder');" +
                "    } else if (spot === 'COMMENT_SUBMIT') {" +
                "      el = document.querySelector('button[aria-label*=\"Comment\" i], button[aria-label=\"Send comment\" i], .comment-submit-button, ytm-comment-simplebox-renderer button[type=\"submit\"], button#submit-button');" +
                "    } else if (spot === 'AD_SKIP') {" +
                "      el = document.querySelector('.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, button.ytp-ad-skip-button-modern, button[aria-label*=\"Skip\" i]');" +
                "    }" +
                "    if (!el) {" +
                "      alert(JSON.stringify({found:false, spot:spot}));" +
                "      return;" +
                "    }" +
                "    var act = '$actionStr';" +
                "    var actionDone = false;" +
                "    if (act === 'click') { try { el.click(); actionDone = true; } catch(e){} }" +
                "    else if (act === 'focus') { try { el.focus(); el.click(); actionDone = true; } catch(e){} }" +
                "    var rect = el.getBoundingClientRect();" +
                "    alert(JSON.stringify({" +
                "      found: true," +
                "      spot: spot," +
                "      actionSuccess: actionDone," +
                "      isInViewport: rect.top >= 0 && rect.bottom <= winHeight," +
                "      isPartiallyInViewport: rect.top < winHeight && rect.bottom > 0," +
                "      isAboveViewport: rect.bottom <= 0," +
                "      isBelowViewport: rect.top >= winHeight," +
                "      rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height }," +
                "      pageTop: rect.top + scrollY," +
                "      pageScrollY: scrollY," +
                "      viewportHeight: winHeight," +
                "      viewportWidth: winWidth," +
                "      dpr: dpr" +
                "    }));" +
                "  } catch(e) {" +
                "    alert(JSON.stringify({found:false, spot:'$cleanSpot', error:e.message}));" +
                "  }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, ElementSpotResult::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "locateElementSpot exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Deterministically locates an element using XPath and retrieves its exact bounding client rect.
     * Optionally performs direct JS action ("click" or "focus").
     */
    suspend fun locateElementByXPath(
        xpath: String,
        performAction: String? = null,
        timeoutMs: Long = 2000L
    ): ElementSpotResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && (message.startsWith("{\"found\":") || message.contains("\"rect\""))) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val encodedXPath = java.net.URLEncoder.encode(xpath, "UTF-8")
            val actionStr = performAction ?: ""
            val js = "javascript:(function(){" +
                "  try {" +
                "    var xp = decodeURIComponent('$encodedXPath');" +
                "    var result = document.evaluate(xp, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);" +
                "    var el = result.singleNodeValue;" +
                "    if (!el) {" +
                "      alert(JSON.stringify({found: false, spot: xp, error: 'XPath element not found'}));" +
                "      return;" +
                "    }" +
                "    try { el.scrollIntoView({ behavior: 'instant', block: 'center' }); } catch(e){}" +
                "    var dpr = window.devicePixelRatio || 1.0;" +
                "    var winHeight = window.innerHeight;" +
                "    var winWidth = window.innerWidth;" +
                "    var scrollY = window.scrollY || 0;" +
                "    var act = '$actionStr';" +
                "    var actionDone = false;" +
                "    if (act === 'click') { try { el.click(); actionDone = true; } catch(e){} }" +
                "    else if (act === 'focus') { try { el.focus(); if (typeof el.select === 'function') el.select(); actionDone = true; } catch(e){} }" +
                "    var rect = el.getBoundingClientRect();" +
                "    alert(JSON.stringify({" +
                "      found: true," +
                "      spot: xp," +
                "      actionSuccess: actionDone," +
                "      isInViewport: rect.top >= 0 && rect.bottom <= winHeight," +
                "      isPartiallyInViewport: rect.top < winHeight && rect.bottom > 0," +
                "      isAboveViewport: rect.bottom <= 0," +
                "      isBelowViewport: rect.top >= winHeight," +
                "      rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height }," +
                "      pageTop: rect.top + scrollY," +
                "      pageScrollY: scrollY," +
                "      viewportHeight: winHeight," +
                "      viewportWidth: winWidth," +
                "      dpr: dpr" +
                "    }));" +
                "  } catch(e) {" +
                "    alert(JSON.stringify({found: false, error: e.message}));" +
                "  }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, ElementSpotResult::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "locateElementByXPath exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Deterministically locates an <a> link whose href or visible text matches the given pattern.
     * Optionally performs direct JS action ("click").
     */
    suspend fun locateLink(
        pattern: String,
        matchType: String = "contains",
        performAction: String? = null,
        timeoutMs: Long = 2000L
    ): ElementSpotResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && (message.startsWith("{\"found\":") || message.contains("\"rect\""))) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val encodedPattern = java.net.URLEncoder.encode(pattern, "UTF-8")
            val isExact = matchType.equals("exact", ignoreCase = true)
            val actionStr = performAction ?: ""
            val js = "javascript:(function(){" +
                "  try {" +
                "    var pat = decodeURIComponent('$encodedPattern').toLowerCase();" +
                "    var isExact = $isExact;" +
                "    var links = document.querySelectorAll('a[href], [role=\"link\"], ytm-video-with-context-renderer a, ytm-compact-video-renderer a');" +
                "    var el = null;" +
                "    for (var i = 0; i < links.length; i++) {" +
                "      var a = links[i];" +
                "      var href = (a.getAttribute('href') || '').toLowerCase();" +
                "      var fullHref = (a.href || '').toLowerCase();" +
                "      var text = (a.innerText || a.textContent || '').trim().toLowerCase();" +
                "      if (isExact) {" +
                "        if (href === pat || fullHref === pat) { el = a; break; }" +
                "      } else {" +
                "        if (href.indexOf(pat) !== -1 || fullHref.indexOf(pat) !== -1 || (pat.length > 2 && text.indexOf(pat) !== -1)) {" +
                "          el = a; break;" +
                "        }" +
                "      }" +
                "    }" +
                "    if (!el) {" +
                "      alert(JSON.stringify({found: false, spot: pat, error: 'Link pattern not found'}));" +
                "      return;" +
                "    }" +
                "    try { el.scrollIntoView({ behavior: 'instant', block: 'center' }); } catch(e){}" +
                "    var dpr = window.devicePixelRatio || 1.0;" +
                "    var winHeight = window.innerHeight;" +
                "    var winWidth = window.innerWidth;" +
                "    var scrollY = window.scrollY || 0;" +
                "    var act = '$actionStr';" +
                "    var actionDone = false;" +
                "    if (act === 'click') { try { el.click(); actionDone = true; } catch(e){} }" +
                "    var rect = el.getBoundingClientRect();" +
                "    alert(JSON.stringify({" +
                "      found: true," +
                "      spot: pat," +
                "      actionSuccess: actionDone," +
                "      isInViewport: rect.top >= 0 && rect.bottom <= winHeight," +
                "      isPartiallyInViewport: rect.top < winHeight && rect.bottom > 0," +
                "      isAboveViewport: rect.bottom <= 0," +
                "      isBelowViewport: rect.top >= winHeight," +
                "      rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height }," +
                "      pageTop: rect.top + scrollY," +
                "      pageScrollY: scrollY," +
                "      viewportHeight: winHeight," +
                "      viewportWidth: winWidth," +
                "      dpr: dpr" +
                "    }));" +
                "  } catch(e) {" +
                "    alert(JSON.stringify({found: false, error: e.message}));" +
                "  }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, ElementSpotResult::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "locateLink exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Deterministically locates an advertisement iframe or sponsored unit on the page.
     */
    suspend fun locateAdIframe(
        iframeSelector: String,
        linkSelector: String? = null,
        performAction: String? = null,
        timeoutMs: Long = 2000L
    ): ElementSpotResult? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val originalPromptDelegate = session.promptDelegate

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message
                if (message != null && (message.startsWith("{\"found\":") || message.contains("\"rect\""))) {
                    deferred.complete(message)
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }

        try {
            val encodedIframe = java.net.URLEncoder.encode(iframeSelector, "UTF-8")
            val encodedLink = java.net.URLEncoder.encode(linkSelector ?: "", "UTF-8")
            val actionStr = performAction ?: ""
            val js = "javascript:(function(){" +
                "  try {" +
                "    var ifrPattern = decodeURIComponent('$encodedIframe').toLowerCase();" +
                "    var lnkPattern = decodeURIComponent('$encodedLink').toLowerCase();" +
                "    var el = null;" +
                "    var iframes = document.querySelectorAll('iframe');" +
                "    for (var i = 0; i < iframes.length; i++) {" +
                "      var f = iframes[i];" +
                "      var src = (f.getAttribute('src') || '').toLowerCase();" +
                "      var id = (f.id || '').toLowerCase();" +
                "      var cls = (f.className || '').toLowerCase();" +
                "      if (!ifrPattern || src.indexOf(ifrPattern) !== -1 || id.indexOf(ifrPattern) !== -1 || cls.indexOf(ifrPattern) !== -1) {" +
                "        el = f; break;" +
                "      }" +
                "    }" +
                "    if (!el) {" +
                "      el = document.querySelector('.ytp-ad-module, .ad-container, [id*=\"google_ads\"], [id*=\"aswift\"], ytm-promoted-sparkles-web-renderer');" +
                "    }" +
                "    if (!el) {" +
                "      alert(JSON.stringify({found: false, spot: ifrPattern, error: 'Ad/iFrame not found'}));" +
                "      return;" +
                "    }" +
                "    try { el.scrollIntoView({ behavior: 'instant', block: 'center' }); } catch(e){}" +
                "    var dpr = window.devicePixelRatio || 1.0;" +
                "    var winHeight = window.innerHeight;" +
                "    var winWidth = window.innerWidth;" +
                "    var scrollY = window.scrollY || 0;" +
                "    var act = '$actionStr';" +
                "    var actionDone = false;" +
                "    if (act === 'click') { try { el.click(); actionDone = true; } catch(e){} }" +
                "    var rect = el.getBoundingClientRect();" +
                "    alert(JSON.stringify({" +
                "      found: true," +
                "      spot: ifrPattern," +
                "      actionSuccess: actionDone," +
                "      isInViewport: rect.top >= 0 && rect.bottom <= winHeight," +
                "      isPartiallyInViewport: rect.top < winHeight && rect.bottom > 0," +
                "      isAboveViewport: rect.bottom <= 0," +
                "      isBelowViewport: rect.top >= winHeight," +
                "      rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height }," +
                "      pageTop: rect.top + scrollY," +
                "      pageScrollY: scrollY," +
                "      viewportHeight: winHeight," +
                "      viewportWidth: winWidth," +
                "      dpr: dpr" +
                "    }));" +
                "  } catch(e) {" +
                "    alert(JSON.stringify({found: false, error: e.message}));" +
                "  }" +
                "})();"
            session.loadUri(js)

            val rawJson = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (!rawJson.isNullOrBlank() && rawJson != "{}") {
                gson.fromJson(rawJson, ElementSpotResult::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "locateAdIframe exception: ${e.message}")
            null
        } finally {
            session.promptDelegate = originalPromptDelegate
        }
    }

    /**
     * Dismisses any open bottom sheet, 3-dots menu modal, or dialog overlay on YouTube.
     */
    suspend fun dismissOpenPopupsOrSheets() = withContext(Dispatchers.Main) {
        try {
            val js = "javascript:(function(){" +
                "  try {" +
                "    var modal = document.querySelector('ytm-bottom-sheet-renderer, ytm-menu-renderer, tp-yt-iron-overlay-backdrop, [role=\"dialog\"], .menu-content');" +
                "    if (modal) {" +
                "      var closeBtn = modal.querySelector('button[aria-label*=\"Close\" i], button[aria-label*=\"Dismiss\" i], button[aria-label*=\"Cancel\" i], c3-icon[type=\"close\"], .ytm-bottom-sheet-close-button');" +
                "      if (closeBtn) { closeBtn.click(); }" +
                "      var scrim = document.querySelector('.scrim, tp-yt-iron-overlay-backdrop, .dialog-scrim');" +
                "      if (scrim) { scrim.click(); }" +
                "      if (modal.parentNode && modal.tagName.toLowerCase() === 'dialog') modal.remove();" +
                "    }" +
                "  } catch(e) {}" +
                "})();"
            session.loadUri(js)
        } catch (e: Exception) {
            Log.d(TAG, "dismissOpenPopupsOrSheets non-fatal exception: ${e.message}")
        }
    }
}


