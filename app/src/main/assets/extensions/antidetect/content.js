(() => {
  'use strict';

  // =========================================================================
  // 1. DYNAMIC PROFILE AUDIO CONTROLLER (SINGLE-ACTIVE AUDIO SINK)
  // =========================================================================
  let isProfileMuted = true; // Default state: all profiles start muted
  const mediaElements = new Set();

  function enforceMuteState(el) {
    if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO' || el instanceof HTMLMediaElement)) {
      try {
        if (isProfileMuted) {
          el.muted = true;
          el.volume = 0.0;
          el.defaultMuted = true;
        } else {
          el.muted = false;
          el.volume = 1.0;
        }
      } catch (e) {}
    }
  }

  function updateYouTubePlayer(muted) {
    try {
      const p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      if (p) {
        if (muted) {
          if (typeof p.mute === 'function') p.mute();
          if (typeof p.setVolume === 'function') p.setVolume(0);
        } else {
          if (typeof p.unMute === 'function') p.unMute();
          if (typeof p.setVolume === 'function') p.setVolume(100);
        }
      }
    } catch (e) {}
  }

  function applyMuteUpdate(muted) {
    isProfileMuted = Boolean(muted);
    mediaElements.forEach((el) => {
      enforceMuteState(el);
    });
    document.querySelectorAll('video, audio').forEach((el) => {
      mediaElements.add(el);
      enforceMuteState(el);
    });
    updateYouTubePlayer(isProfileMuted);
  }

  // Observer catches pre-roll ads, injected videos, and dynamic iframes
  try {
    const mediaObserver = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (node && (node.tagName === 'VIDEO' || node.tagName === 'AUDIO' || node instanceof HTMLMediaElement)) {
            mediaElements.add(node);
            enforceMuteState(node);
          } else if (node && node.querySelectorAll) {
            node.querySelectorAll('video, audio').forEach((el) => {
              mediaElements.add(el);
              enforceMuteState(el);
            });
          }
        }
      }
    });

    if (document.documentElement) {
      mediaObserver.observe(document.documentElement, { childList: true, subtree: true });
    }
  } catch (e) {}

  // Scan existing elements on initialization
  try {
    document.querySelectorAll('video, audio').forEach((el) => {
      mediaElements.add(el);
      enforceMuteState(el);
    });
  } catch (e) {}

  // Event-level enforcement for play/playing events
  window.addEventListener('play', (e) => {
    if (isProfileMuted) enforceMuteState(e.target);
  }, true);
  window.addEventListener('playing', (e) => {
    if (isProfileMuted) enforceMuteState(e.target);
  }, true);

  // Listen for native Android runtime commands via WebExtension runtime messaging
  if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.onMessage) {
    browser.runtime.onMessage.addListener((message) => {
      if (message && message.type === "SET_MUTE_STATE") {
        applyMuteUpdate(message.muted);
        return Promise.resolve({ success: true, isMuted: isProfileMuted });
      }
    });
  }

  // Fallback direct window message listener for rapid eval execution
  window.addEventListener('message', (event) => {
    if (event.data && event.data.type === 'SET_MUTE_STATE') {
      applyMuteUpdate(event.data.muted);
    }
  });

  // =========================================================================
  // 2. BACKGROUND ANTI-PAUSE & PAGE VISIBILITY SHIELD
  // (Prevents background video halting, buffering or freezing)
  // =========================================================================
  try {
    const shieldCode = `
      (() => {
        'use strict';
        const nativeToString = Function.prototype.toString;
        const registry = new WeakMap();

        function wrapNative(fn, name) {
          registry.set(fn, 'function ' + name + '() { [native code] }');
          return fn;
        }

        Function.prototype.toString = new Proxy(nativeToString, {
          apply(target, thisArg, args) {
            if (registry.has(thisArg)) {
              return registry.get(thisArg);
            }
            return Reflect.apply(target, thisArg, args);
          }
        });
        wrapNative(Function.prototype.toString, 'toString');

        // --- PREVENT YOUTUBE & SITES FROM DETECTING TAB INACTIVE / BACKGROUND PAUSE ---
        try {
          Object.defineProperty(Document.prototype, 'hidden', {
            get: wrapNative(function() { return false; }, 'get hidden'),
            configurable: false,
            enumerable: true
          });

          Object.defineProperty(Document.prototype, 'visibilityState', {
            get: wrapNative(function() { return 'visible'; }, 'get visibilityState'),
            configurable: false,
            enumerable: true
          });

          window.addEventListener('visibilitychange', (e) => {
            e.stopImmediatePropagation();
          }, true);
          document.addEventListener('visibilitychange', (e) => {
            e.stopImmediatePropagation();
          }, true);

          Document.prototype.hasFocus = wrapNative(function() { return true; }, 'hasFocus');
        } catch(e) {}

        // --- YOUTUBE 240P CLAMP & BACKGROUND ANTI-PAUSE WATCHDOG ---
        try {
          const youtubeWatchdog = () => {
            const p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
            if (p) {
              if (typeof p.setPlaybackQualityRange === 'function') p.setPlaybackQualityRange('tiny', 'tiny');
              if (typeof p.setPlaybackQuality === 'function') p.setPlaybackQuality('tiny');

              const confirmBtn = document.querySelector('yt-confirm-dialog-renderer #confirm-button button, .ytp-bezel-text');
              if (confirmBtn) {
                try { confirmBtn.click(); } catch(e) {}
              }
            }
          };

          setInterval(youtubeWatchdog, 600);
        } catch(e) {}
      })();
    `;

    const scriptEl = document.createElement('script');
    scriptEl.textContent = shieldCode;
    (document.head || document.documentElement).appendChild(scriptEl);
    scriptEl.remove();
  } catch (e) {}

  // =========================================================================
  // 3. YOUTUBE LOCALSTORAGE 240P PERSISTENCE
  // =========================================================================
  try {
    localStorage.setItem('yt-player-quality', JSON.stringify({
      data: "tiny",
      expiration: Date.now() + 86400000,
      creation: Date.now()
    }));
  } catch (e) {}

  // =========================================================================
  // 4. HARDWARE SPOOFING & VIDEO RESUME ENGINE
  // =========================================================================
  if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.sendMessage) {
    browser.runtime.sendMessage({ type: "FETCH_CONFIG" }, (response) => {
      if (!response || !response.config) return;
      const cfg = response.config;

      const hwCode = `
        (() => {
          'use strict';
          const nativeToString = Function.prototype.toString;
          const registry = new WeakMap();

          function wrapNative(fn, name) {
            registry.set(fn, 'function ' + name + '() { [native code] }');
            return fn;
          }

          if ('hardwareConcurrency' in Navigator.prototype || ${cfg.hardwareConcurrency || 0} > 0) {
            try {
              Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', {
                get: wrapNative(() => ${cfg.hardwareConcurrency}, 'get hardwareConcurrency'),
                enumerable: true,
                configurable: true
              });
            } catch (e) {}
          }

          if ('deviceMemory' in Navigator.prototype || ${cfg.deviceMemory || 0} > 0) {
            try {
              Object.defineProperty(Navigator.prototype, 'deviceMemory', {
                get: wrapNative(() => ${cfg.deviceMemory}, 'get deviceMemory'),
                enumerable: true,
                configurable: true
              });
            } catch (e) {}
          }

          if (${cfg.screenWidth || 0} > 0 && ${cfg.screenHeight || 0} > 0) {
            try {
              Object.defineProperty(Screen.prototype, 'width', {
                get: wrapNative(() => ${cfg.screenWidth}, 'get width'),
                enumerable: true,
                configurable: true
              });
              Object.defineProperty(Screen.prototype, 'availWidth', {
                get: wrapNative(() => ${cfg.screenWidth}, 'get availWidth'),
                enumerable: true,
                configurable: true
              });
              Object.defineProperty(Screen.prototype, 'height', {
                get: wrapNative(() => ${cfg.screenHeight}, 'get height'),
                enumerable: true,
                configurable: true
              });
              Object.defineProperty(Screen.prototype, 'availHeight', {
                get: wrapNative(() => ${cfg.screenHeight}, 'get availHeight'),
                enumerable: true,
                configurable: true
              });
            } catch (e) {}
          }

          if (${cfg.devicePixelRatio || 0} > 0) {
            try {
              Object.defineProperty(window, 'devicePixelRatio', {
                get: wrapNative(() => ${cfg.devicePixelRatio}, 'get devicePixelRatio'),
                enumerable: true,
                configurable: true
              });
            } catch (e) {}
          }

          const hookWebGL = (proto) => {
            if (!proto) return;
            const origGetParam = proto.prototype.getParameter;
            proto.prototype.getParameter = wrapNative(function(param) {
              if (param === 0x9245) return '${cfg.webGlVendor}';
              if (param === 0x9246) return '${cfg.webGlRenderer}';
              return origGetParam.call(this, param);
            }, 'getParameter');
          };

          if (window.WebGLRenderingContext) hookWebGL(WebGLRenderingContext);
          if (window.WebGL2RenderingContext) hookWebGL(WebGL2RenderingContext);
        })();
      `;

      const hwScript = document.createElement('script');
      hwScript.textContent = hwCode;
      (document.head || document.documentElement).appendChild(hwScript);
      hwScript.remove();

      // Auto Video Resume Engine
      try {
        const resumeKey = '__video_resume_' + location.hostname + location.pathname + location.search;

        function bindVideoResume(video) {
          if (!video || video.__resumeHooked) return;
          video.__resumeHooked = true;

          let savedSec = parseFloat(localStorage.getItem(resumeKey) || '0');
          const urlMatch = location.search.match(/[?&]t=(\d+)s?/i) || location.hash.match(/[#&]t=(\d+)s?/i);
          if (urlMatch && urlMatch[1]) {
            const urlSec = parseFloat(urlMatch[1]);
            if (urlSec > savedSec) savedSec = urlSec;
          }

          const applyResume = () => {
            if (savedSec > 2 && (!video.duration || savedSec < video.duration - 2)) {
              if (Math.abs(video.currentTime - savedSec) > 2) {
                video.currentTime = savedSec;
              }
            }
          };

          if (video.readyState >= 1) {
            applyResume();
          } else {
            video.addEventListener('loadedmetadata', applyResume, { once: true });
            video.addEventListener('canplay', applyResume, { once: true });
          }

          video.addEventListener('timeupdate', () => {
            if (video.currentTime > 2 && (!video.duration || video.currentTime < video.duration - 2)) {
              localStorage.setItem(resumeKey, video.currentTime.toString());
              if (location.hostname.includes('youtube.com') && location.pathname.includes('/watch')) {
                try {
                  const url = new URL(location.href);
                  url.searchParams.set('t', Math.floor(video.currentTime) + 's');
                  history.replaceState(history.state, '', url.toString());
                } catch (e) {}
              }
            }
          });

          video.addEventListener('pause', () => {
            if (video.currentTime > 2) {
              localStorage.setItem(resumeKey, video.currentTime.toString());
            }
          });
        }

        const scanAndBind = () => {
          document.querySelectorAll('video').forEach(bindVideoResume);
        };

        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', scanAndBind);
        } else {
          scanAndBind();
        }

        const observer = new MutationObserver(scanAndBind);
        observer.observe(document.documentElement || document.body, { childList: true, subtree: true });
      } catch (e) {}
    });
  }
})();
