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
        elements.push({
          id: node.id || `el_${index}`,
          role: node.getAttribute('role') || node.tagName.toLowerCase(),
          tag: node.tagName.toLowerCase(),
          text: (node.innerText || node.value || '').trim().substring(0, 100),
          ariaLabel: node.getAttribute('aria-label') || '',
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
      elements: elements.slice(0, 60), // Keep payload lightweight (< 3KB)
      videoState: videoEl ? {
        currentTime: Math.floor(videoEl.currentTime),
        duration: Math.floor(videoEl.duration || 0),
        paused: videoEl.paused,
        ended: videoEl.ended
      } : null
    });
  };

  // 3. Local Tier-1 Autonomous Skip Observer (Zero Token Ad Dismissal)
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
