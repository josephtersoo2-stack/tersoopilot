(() => {
  'use strict';

  // 1. Page State Classifier
  window.detectBrowserPageState = function() {
    const url = window.location.href;
    
    // Cookie / Privacy Consent Banners
    if (document.querySelector('.consent-bump-v2, #consent-bump, ytd-consent-bump-v2-lightbox, .upsell-dialog-renderer')) {
      return "CONSENT_WALL";
    }
    // Active Pre-roll or Mid-roll Ad
    if (document.querySelector('.ad-showing, .ytp-ad-player-overlay, .ytp-ad-skip-button')) {
      return "AD_ACTIVE";
    }
    // Search input active / focused
    if (document.querySelector('form#search-form[aria-expanded="true"], input#search[aria-expanded="true"]')) {
      return "SEARCH_INPUT_ACTIVE";
    }
    // Search Results Page
    if (url.includes('/results?search_query=') || url.includes('/results?q=')) {
      return "SEARCH_RESULTS";
    }
    // Video Watch Page
    if (url.includes('/watch') || document.querySelector('video.html5-main-video')) {
      return "VIDEO_PLAYBACK";
    }
    // Vertical Shorts Feed
    if (url.includes('/shorts/')) {
      return "SHORTS_ACTIVE";
    }
    
    return "PAGE_READY";
  };

  // 2. Structured DOM Snapshot Extractor
  window.extractDomSnapshot = function() {
    const dpr = window.devicePixelRatio || 1.0;
    const elements = [];
    
    // Query meaningful interactable elements and semantic containers
    const nodes = document.querySelectorAll(
      'button, a, input, select, textarea, [role="button"], [role="searchbox"], video, ytd-video-renderer, ytm-video-with-context-renderer'
    );

    nodes.forEach((node, index) => {
      const rect = node.getBoundingClientRect();
      // Filter elements rendered within current scroll threshold (-200px to +200px of viewport)
      const isRendered = rect.width > 0 && rect.height > 0 && 
                         rect.top < (window.innerHeight + 200) && rect.bottom > -200;

      if (isRendered) {
        const aria = (node.getAttribute('aria-label') || '').toLowerCase();
        const text = (node.innerText || node.value || '').trim();
        let href = node.getAttribute('href') || '';
        if (!href && node.querySelector) {
          const childLink = node.querySelector('a[href*="/watch?v="]');
          if (childLink) href = childLink.getAttribute('href') || '';
        }
        if (!href && node.closest) {
          const parentCard = node.closest('ytm-video-with-context-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-rich-item-renderer');
          if (parentCard) {
            const cardLink = parentCard.querySelector('a[href*="/watch?v="]');
            if (cardLink) href = cardLink.getAttribute('href') || '';
          }
        }

        const id = (node.id || '').toLowerCase();
        const cls = (node.className || '').toString().toLowerCase();

        let actionType = null;
        if (
          (aria.includes('like this video') || (aria.includes('like') && !aria.includes('dislike')) || id.includes('like-button') || cls.includes('like-button')) &&
          !aria.includes('dislike') && !cls.includes('dislike')
        ) {
          actionType = 'LIKE';
        } else if (
          (aria.includes('subscribe') || text.toLowerCase() === 'subscribe' || cls.includes('subscribe')) &&
          !aria.includes('unsubscribe') && !text.toLowerCase().includes('subscribed')
        ) {
          actionType = 'SUBSCRIBE';
        } else if (
          aria.includes('comment') || cls.includes('comment') || (node.closest && node.closest('ytm-comments-entry-point-header-renderer, #comments-button'))
        ) {
          actionType = 'COMMENTS';
        } else if (
          href.includes('/watch?v=') || (node.closest && node.closest('ytm-video-with-context-renderer, ytd-video-renderer'))
        ) {
          actionType = 'VIDEO_CARD';
        }

        elements.push({
          id: node.id || `el_${index}`,
          role: node.getAttribute('role') || node.tagName.toLowerCase(),
          tag: node.tagName.toLowerCase(),
          text: text.substring(0, 100),
          ariaLabel: node.getAttribute('aria-label') || '',
          href: href,
          actionType: actionType,
          visible: rect.top >= 0 && rect.bottom <= window.innerHeight,
          enabled: !node.disabled,
          rect: {
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height
          }
        });
      }
    });

    const videoEl = document.querySelector('video');

    // Prioritize action targets (likes, subscribes, comments, video cards) so they are never truncated
    elements.sort((a, b) => {
      const aPriority = a.actionType ? 1 : 0;
      const bPriority = b.actionType ? 1 : 0;
      return bPriority - aPriority;
    });

    return JSON.stringify({
      url: window.location.href,
      title: document.title,
      pageState: window.detectBrowserPageState(),
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight,
        dpr: dpr,
        scrollX: window.scrollX || 0,
        scrollY: window.scrollY || 0
      },
      elements: elements.slice(0, 150), // Expanded quota for comprehensive DOM resolution
      videoState: videoEl ? {
        currentTime: Math.floor(videoEl.currentTime),
        duration: Math.floor(videoEl.duration || 0),
        paused: videoEl.paused,
        ended: videoEl.ended
      } : null
    });
  };

  // Helper: Extract clean 11-char YouTube Video ID from any URL or raw string
  function extractCleanVideoId(target) {
    if (!target) return '';
    const str = String(target).trim();
    const match = str.match(/(?:v=|\/shorts\/|\/embed\/|\.be\/|vi\/)([a-zA-Z0-9_-]{11})/);
    if (match && match[1]) return match[1];
    if (/^[a-zA-Z0-9_-]{11}$/.test(str)) return str;
    return str;
  }

  // 3. Verify Search Input & Expanded State
  window.verifySearchInputOpen = function() {
    const inp = document.querySelector(
      'input.search-input, input[type="search"], input[name="search_query"], input#search, form#search-form input, ytm-searchbox input'
    );
    if (!inp) {
      return JSON.stringify({ found: false, open: false, focused: false, reason: "NOT_IN_DOM" });
    }
    const rect = inp.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1.0;
    const isVisible = rect.width > 0 && rect.height > 0 && rect.bottom > 0;
    const isFocused = (document.activeElement === inp);
    return JSON.stringify({
      found: true,
      open: isVisible,
      focused: isFocused,
      rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
      dpr: dpr
    });
  };

  // 4. Internal Top 20 Results Scanner for Target Video URL/ID
  window.scanSearchResults = function(targetUrlOrId, maxResults, targetCleanTitle) {
    const limit = maxResults || 20;
    const targetVideoId = extractCleanVideoId(targetUrlOrId);
    const targetStr = (targetUrlOrId || '').toLowerCase().trim();
    const titleTarget = (targetCleanTitle || '').toLowerCase().trim();
    const dpr = window.devicePixelRatio || 1.0;
    const winHeight = window.innerHeight;
    const winWidth = window.innerWidth;

    const cardSelectors = [
      'ytm-video-with-context-renderer',
      'ytd-video-renderer',
      'ytm-compact-video-renderer',
      'ytd-compact-video-renderer',
      'ytd-rich-item-renderer',
      'div.media-item',
      'a.media-item-thumbnail-container'
    ];

    let rawElements = Array.from(document.querySelectorAll(cardSelectors.join(',')));
    const seen = new Set();
    const cards = [];

    for (let el of rawElements) {
      const topCard = el.closest('ytm-video-with-context-renderer, ytd-video-renderer, ytm-compact-video-renderer, ytd-rich-item-renderer') || el;
      if (!seen.has(topCard)) {
        seen.add(topCard);
        cards.push(topCard);
        if (cards.length >= limit) break;
      }
    }

    if (cards.length === 0) {
      const watchLinks = Array.from(document.querySelectorAll('a[href*="/watch?v="], a[href*="shorts/"]'));
      for (let link of watchLinks) {
        if (!seen.has(link)) {
          seen.add(link);
          cards.push(link);
          if (cards.length >= limit) break;
        }
      }
    }

    let targetFound = false;
    let targetIndex = -1;
    let targetItem = null;

    for (let i = 0; i < cards.length; i++) {
      const c = cards[i];
      // Search all links in card to prioritize actual video watch link over channel avatar links
      const links = Array.from(c.tagName === 'A' ? [c] : c.querySelectorAll('a[href]'));
      let bestHref = '';
      let vId = '';
      for (let l of links) {
        const h = l.getAttribute('href') || '';
        const parsedId = extractCleanVideoId(h);
        if (parsedId && (h.includes('/watch') || h.includes('shorts/'))) {
          bestHref = h;
          vId = parsedId;
          break;
        }
      }
      if (!bestHref && links.length > 0) {
        bestHref = links[0].getAttribute('href') || '';
        vId = extractCleanVideoId(bestHref) || '';
      }

      // Check thumbnail images for video ID (always present in YouTube mobile search)
      if (!vId) {
        const imgs = Array.from(c.querySelectorAll('img[src*="/vi/"], img[src*="ytimg"]'));
        for (let img of imgs) {
          const s = img.getAttribute('src') || '';
          const parsedId = extractCleanVideoId(s);
          if (parsedId) {
            vId = parsedId;
            break;
          }
        }
      }

      // Check custom video-id attributes
      if (!vId) {
        vId = c.getAttribute('data-video-id') || c.getAttribute('data-context-item-id') || '';
      }

      const titleEl = c.querySelector('h3, .media-item-headline, .ytm-badge-and-title, a[aria-label]');
      const title = titleEl ? (titleEl.innerText || titleEl.getAttribute('aria-label') || '').trim() : '';

      const rect = c.getBoundingClientRect();
      const thumbEl = c.querySelector('a.media-item-thumbnail-container, .video-thumbnail-container, .thumbnail, img') || c;
      const thumbRect = thumbEl ? thumbEl.getBoundingClientRect() : rect;
      let isTarget = false;
      if (targetVideoId && vId === targetVideoId) {
        isTarget = true;
      } else if (targetVideoId && (bestHref.includes(targetVideoId) || c.innerHTML.includes(targetVideoId))) {
        isTarget = true;
      } else if (titleTarget && title.toLowerCase().includes(titleTarget)) {
        isTarget = true;
      } else if (targetStr && (bestHref.toLowerCase().includes(targetStr) || title.toLowerCase().includes(targetStr) || c.innerHTML.toLowerCase().includes(targetStr))) {
        isTarget = true;
      } else if (targetStr && targetStr.length > 15) {
        // Fallback: match by title keywords (at least 3 keywords)
        const cleanWords = targetStr.toLowerCase().replace(/[^a-z0-9 ]/g, ' ').split(/\s+/).filter(w => w.length > 3);
        if (cleanWords.length > 0) {
          let matched = 0;
          const lowTitle = (title + ' ' + (c.innerText || '')).toLowerCase();
          for (let w of cleanWords) {
            if (lowTitle.includes(w)) matched++;
          }
          if (matched >= Math.min(3, cleanWords.length)) {
            isTarget = true;
          }
        }
      }

      const item = {
        index: i,
        videoId: vId,
        href: bestHref,
        title: title.slice(0, 100),
        isTarget: isTarget,
        rect: (thumbRect && thumbRect.width > 20 && thumbRect.height > 20)
          ? { left: thumbRect.left, top: thumbRect.top, width: thumbRect.width, height: thumbRect.height }
          : { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
        cardRect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
        isInViewport: rect.top >= 0 && rect.bottom <= winHeight,
        isAboveViewport: rect.bottom <= 0,
        isBelowViewport: rect.top >= winHeight
      };

      if (isTarget && !targetFound) {
        targetFound = true;
        targetIndex = i;
        targetItem = item;
      }
    }

    return JSON.stringify({
      totalFound: cards.length,
      targetFound: targetFound,
      targetIndex: targetIndex,
      targetItem: targetItem,
      dpr: dpr,
      viewportHeight: winHeight,
      viewportWidth: winWidth
    });
  };

  // Backward compatibility alias for single card scanning
  window.scanForVideoCard = function(targetUrlOrId, targetCleanTitle) {
    const scan = JSON.parse(window.scanSearchResults(targetUrlOrId, 20, targetCleanTitle));
    if (scan.targetFound && scan.targetItem) {
      return JSON.stringify({
        found: true,
        videoId: scan.targetItem.videoId,
        isInViewport: scan.targetItem.isInViewport,
        isPartiallyInViewport: !scan.targetItem.isAboveViewport && !scan.targetItem.isBelowViewport,
        isAboveViewport: scan.targetItem.isAboveViewport,
        isBelowViewport: scan.targetItem.isBelowViewport,
        rect: scan.targetItem.rect,
        dpr: scan.dpr,
        viewportHeight: scan.viewportHeight,
        viewportWidth: scan.viewportWidth
      });
    }
    return JSON.stringify({ found: false, totalFound: scan.totalFound });
  };

  // 4. Precision Spot Finder for Core YouTube Interactions
  window.getYouTubeElementSpot = function(spotType, performAction) {
    const dpr = window.devicePixelRatio || 1.0;
    const winHeight = window.innerHeight;
    const winWidth = window.innerWidth;
    const scrollY = window.scrollY || 0;

    let el = null;
    const spot = (spotType || '').toUpperCase();

    switch (spot) {
      case 'SEARCH_BUTTON':
        // Homepage top-right magnifying glass button (strict right boundary > 0.6)
        const btns = document.querySelectorAll('button, a, c3-icon, ytm-search-icon, .search-icon, [role="button"]');
        for (let i = 0; i < btns.length; i++) {
          const b = btns[i];
          const rect = b.getBoundingClientRect();
          if (rect.width > 0 && rect.top >= 0 && rect.top < 80 && rect.right > winWidth * 0.6) {
            const aria = (b.getAttribute('aria-label') || '').toLowerCase();
            if (aria.indexOf('search') !== -1 || b.tagName.toLowerCase() === 'ytm-search-icon' || b.querySelector('c3-icon[type="search"]')) {
              el = b.closest('button') || b.closest('a') || b;
              break;
            }
          }
        }
        break;

      case 'SEARCH_INPUT_BOX':
        // Search results page search pill/header box
        el = document.querySelector(
          '.searchbox-placeholder, form#search-form, ytm-searchbox, .header-bar-search-box, input.search-input'
        );
        break;

      case 'SEARCH_INPUT':
        el = document.querySelector(
          'input.search-input, input[type="search"], input[name="search_query"], input#search, form#search-form input, ytm-searchbox input'
        );
        break;

      case 'DESCRIPTION_BUTTON':
        el = document.querySelector(
          'ytm-structured-description-header-renderer, .expand-description, #description, ytm-expandable-video-description-body-renderer, .ytm-description-header'
        );
        if (!el) {
          const candidates = Array.from(document.querySelectorAll('span, button, div'));
          for (let i = 0; i < candidates.length; i++) {
            const t = (candidates[i].innerText || '').trim().toLowerCase();
            if (t === '...more' || t === 'more') {
              el = candidates[i];
              break;
            }
          }
        }
        break;

      case 'DESCRIPTION_CLOSE':
      case 'COMMENTS_CLOSE':
        el = document.querySelector(
          'button[aria-label*="Close" i], .ytm-bottom-sheet-close-button, c3-icon[type="close"], [aria-label*="Dismiss" i]'
        );
        break;

      case 'LIKE_BUTTON':
        el = document.querySelector(
          'button[aria-label*="like this video" i], ytm-like-button-renderer button, button.like-button, [aria-label*="like" i]:not([aria-label*="dislike" i])'
        );
        break;

      case 'SUBSCRIBE_BUTTON':
        el = document.querySelector(
          'button[aria-label*="Subscribe" i], ytm-subscribe-button-renderer button, button.subscribe-button, [aria-label*="Subscribe to" i], .ytm-subscribe-button'
        );
        break;

      case 'COMMENTS_BUTTON':
        el = document.querySelector(
          'ytm-comments-entry-point-header-renderer, #comments-button, [aria-label*="Comments" i], [aria-label*="comment" i], .ytm-comments-header'
        );
        break;

      case 'COMMENT_INPUT':
        el = document.querySelector(
          'input[name="comment_text"], [aria-label*="Add a comment" i], #comment-simplebox input, #comment-simplebox textarea, ytm-comment-simplebox-renderer input, textarea[aria-label*="comment" i], #simplebox-placeholder'
        );
        break;

      case 'COMMENT_SUBMIT':
        el = document.querySelector(
          'button[aria-label*="Comment" i], button[aria-label="Send comment" i], .comment-submit-button, ytm-comment-simplebox-renderer button[type="submit"], button#submit-button'
        );
        break;

      case 'AD_SKIP':
        el = document.querySelector(
          '.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, button.ytp-ad-skip-button-modern, button[aria-label*="Skip" i]'
        );
        break;
    }

    if (!el) {
      return JSON.stringify({ found: false, spot: spot });
    }

    let actionDone = false;
    if (performAction === 'click') {
      try { el.click(); actionDone = true; } catch (e) {}
    } else if (performAction === 'focus') {
      try { el.focus(); el.click(); actionDone = true; } catch (e) {}
    }

    const rect = el.getBoundingClientRect();
    const isInViewport = rect.top >= 0 && rect.bottom <= winHeight;

    return JSON.stringify({
      found: true,
      spot: spot,
      actionSuccess: actionDone,
      isInViewport: isInViewport,
      isPartiallyInViewport: rect.top < winHeight && rect.bottom > 0,
      isAboveViewport: rect.bottom <= 0,
      isBelowViewport: rect.top >= winHeight,
      rect: {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height
      },
      pageTop: rect.top + scrollY,
      pageScrollY: scrollY,
      viewportHeight: winHeight,
      viewportWidth: winWidth,
      dpr: dpr
    });
  };

  // 5. Layout Calibration Engine (12-Hour Spatial Anchor Extraction)
  window.calibrateSpatialAnchors = function() {
    const winWidth = window.innerWidth || 1;
    const winHeight = window.innerHeight || 1;
    const anchors = {};

    function record(key, elem) {
      if (!elem) return;
      const rect = elem.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        const centerX = Math.round(((rect.left + rect.width / 2) / winWidth) * 1000);
        const centerY = Math.round(((rect.top + rect.height / 2) / winHeight) * 1000);
        anchors[key] = {
          x: Math.max(0, Math.min(1000, centerX)),
          y: Math.max(0, Math.min(1000, centerY))
        };
      }
    }

    // 1. Search Icon (Homepage top-right)
    const searchIcons = document.querySelectorAll('button, a, c3-icon, ytm-search-icon, .search-icon, [role="button"]');
    for (let i = 0; i < searchIcons.length; i++) {
      const b = searchIcons[i];
      const r = b.getBoundingClientRect();
      if (r.width > 0 && r.top >= 0 && r.top < 80 && r.right > winWidth * 0.6) {
        const aria = (b.getAttribute('aria-label') || '').toLowerCase();
        if (aria.includes('search') || b.tagName.toLowerCase() === 'ytm-search-icon' || b.querySelector('c3-icon[type="search"]')) {
          record('SEARCH_ICON', b.closest('button') || b.closest('a') || b);
          break;
        }
      }
    }

    // 2. Search Input Box (Search Results page pill)
    const searchBox = document.querySelector('.searchbox-placeholder, form#search-form, ytm-searchbox');
    record('SEARCH_INPUT_BOX', searchBox);

    // 3. Like Button
    const likeBtn = document.querySelector('button[aria-label*="like this video" i], ytm-like-button-renderer button, button.like-button, [aria-label*="like" i]:not([aria-label*="dislike" i])');
    record('LIKE_BUTTON', likeBtn);

    // 4. Subscribe Button
    const subBtn = document.querySelector('button[aria-label*="Subscribe" i], ytm-subscribe-button-renderer button, button.subscribe-button, [aria-label*="Subscribe to" i], .ytm-subscribe-button');
    record('SUBSCRIBE_BUTTON', subBtn);

    // 5. Description Expander
    const descBtn = document.querySelector('ytm-structured-description-header-renderer, .expand-description, #description, ytm-expandable-video-description-body-renderer, .ytm-description-header');
    record('DESCRIPTION_EXPANDER', descBtn);

    // 6. Description / Comments Close
    const closeBtn = document.querySelector('button[aria-label*="Close" i], .ytm-bottom-sheet-close-button, c3-icon[type="close"], [aria-label*="Dismiss" i]');
    record('DESCRIPTION_CLOSE', closeBtn);
    record('COMMENTS_CLOSE', closeBtn);

    // 7. Comments Entry
    const commentsBtn = document.querySelector('ytm-comments-entry-point-header-renderer, #comments-button, [aria-label*="Comments" i]');
    record('COMMENTS_ENTRY', commentsBtn);

    // 8. Bottom Navigation Tabs
    const tabHome = document.querySelector('ytm-pivot-bar-item-renderer:nth-child(1), ytm-pivot-bar-item:nth-child(1)');
    record('TAB_HOME', tabHome);
    const tabShorts = document.querySelector('ytm-pivot-bar-item-renderer:nth-child(2), ytm-pivot-bar-item:nth-child(2)');
    record('TAB_SHORTS', tabShorts);
    const tabSubs = document.querySelector('ytm-pivot-bar-item-renderer:nth-child(3), ytm-pivot-bar-item:nth-child(3)');
    record('TAB_SUBSCRIPTIONS', tabSubs);
    const tabYou = document.querySelector('ytm-pivot-bar-item-renderer:nth-child(4), ytm-pivot-bar-item:nth-child(4)');
    record('TAB_YOU', tabYou);

    return JSON.stringify({
      calibratedAt: Date.now(),
      viewportWidth: winWidth,
      viewportHeight: winHeight,
      anchors: anchors
    });
  };

  // 6. Playback Bitrate Capper (Restricts to 144p / tiny to conserve memory & GPU)
  window.enforceLowestBitrate = function() {
    let success = false;
    try {
      const player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      if (player && typeof player.setPlaybackQualityRange === 'function') {
        player.setPlaybackQualityRange('tiny', 'tiny');
        success = true;
      } else if (player && typeof player.setPlaybackQuality === 'function') {
        player.setPlaybackQuality('tiny');
        success = true;
      }
    } catch (e) {}
    return JSON.stringify({ success: success, quality: 'tiny' });
  };

  // Expose to window.wrappedJSObject for page context access in GeckoView
  try {
    if (typeof window.wrappedJSObject !== 'undefined') {
      window.wrappedJSObject.detectBrowserPageState = window.detectBrowserPageState;
      window.wrappedJSObject.extractDomSnapshot = window.extractDomSnapshot;
      window.wrappedJSObject.scanForVideoCard = window.scanForVideoCard;
      window.wrappedJSObject.scanSearchResults = window.scanSearchResults;
      window.wrappedJSObject.verifySearchInputOpen = window.verifySearchInputOpen;
      window.wrappedJSObject.getYouTubeElementSpot = window.getYouTubeElementSpot;
      window.wrappedJSObject.calibrateSpatialAnchors = window.calibrateSpatialAnchors;
      window.wrappedJSObject.enforceLowestBitrate = window.enforceLowestBitrate;
    }
  } catch (e) {}

  // 7. Local Tier-1 Autonomous Skip Observer (Zero Token Ad Dismissal)
  const autoSkipObserver = new MutationObserver(() => {
    const skipBtn = document.querySelector(
      '.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, button.ytp-ad-skip-button-modern'
    );
    if (skipBtn && skipBtn.offsetParent !== null) {
      skipBtn.click();
    }
  });
  autoSkipObserver.observe(document.documentElement, { childList: true, subtree: true });
})();
