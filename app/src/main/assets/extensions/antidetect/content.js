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
          if (el.paused) {
            el.play().catch(() => {});
          }
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
          if (typeof p.getPlayerState === 'function' && p.getPlayerState() === 2) {
            p.playVideo();
          }
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
      if (message && message.type === "SET_VIDEO_RESOLUTION") {
        window.postMessage({ type: 'INTERNAL_SET_YT_RESOLUTION', resolution: message.resolution }, '*');
        return Promise.resolve({ success: true, resolution: message.resolution });
      }
    });
  }

  // Fallback direct window message listener for rapid eval execution
  window.addEventListener('message', (event) => {
    if (event.data && event.data.type === 'SET_MUTE_STATE') {
      applyMuteUpdate(event.data.muted);
    }
    if (event.data && event.data.type === 'SET_VIDEO_RESOLUTION') {
      window.postMessage({ type: 'INTERNAL_SET_YT_RESOLUTION', resolution: event.data.resolution }, '*');
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

        // --- YOUTUBE BACKEND-ENFORCED RESOLUTION & BACKGROUND ANTI-PAUSE WATCHDOG ---
        try {
          const QUALITY_MAP = {
            '144p': 'tiny',
            '240p': 'small',
            '360p': 'medium',
            '480p': 'large',
            '720p': 'hd720',
            '1080p': 'hd1080',
            'tiny': 'tiny',
            'small': 'small',
            'medium': 'medium',
            'large': 'large',
            'hd720': 'hd720',
            'hd1080': 'hd1080'
          };

          let currentTargetResolution = '240p';
          let userManualOverride = false;
          let isApplyingQuality = false;

          function getMappedQuality(res) {
            return QUALITY_MAP[res] || QUALITY_MAP['240p'] || 'small';
          }

          function applyPlayerQuality(p, res) {
            if (!p) return;
            const ytQuality = getMappedQuality(res);
            isApplyingQuality = true;
            try {
              if (typeof p.setPlaybackQualityRange === 'function') {
                p.setPlaybackQualityRange(ytQuality, ytQuality);
              }
              if (typeof p.setPlaybackQuality === 'function') {
                p.setPlaybackQuality(ytQuality);
              }
              localStorage.setItem('yt-player-quality', JSON.stringify({
                data: ytQuality,
                expiration: Date.now() + 86400000,
                creation: Date.now()
              }));
            } catch(e) {}
            setTimeout(() => { isApplyingQuality = false; }, 600);
          }

          // Hook player events to detect user manual changes and handle video loads
          function hookYouTubePlayer(p) {
            if (!p || p.__shieldHooked) return;
            p.__shieldHooked = true;

            try {
              if (typeof p.addEventListener === 'function') {
                p.addEventListener('onPlaybackQualityChange', () => {
                  if (!isApplyingQuality) {
                    // User manually changed quality inside YouTube player
                    userManualOverride = true;
                  }
                });
                p.addEventListener('onStateChange', (state) => {
                  // State 1 is PLAYING. If not manually overridden, ensure target resolution is applied
                  if (state === 1 && !userManualOverride) {
                    applyPlayerQuality(p, currentTargetResolution);
                  }
                });
              }
            } catch(e) {}

            if (!userManualOverride) {
              applyPlayerQuality(p, currentTargetResolution);
            }
          }

          // Detect manual interaction with YouTube settings/quality menu
          document.addEventListener('click', (e) => {
            if (e.target && e.target.closest && (
              e.target.closest('.ytp-settings-menu') ||
              e.target.closest('.ytp-panel-menu') ||
              e.target.closest('.ytp-quality-menu') ||
              e.target.closest('.ytp-settings-button')
            )) {
              userManualOverride = true;
            }
          }, true);

          // Handle incoming resolution change ping from backend/native
          window.addEventListener('message', (e) => {
            if (e.data && e.data.type === 'INTERNAL_SET_YT_RESOLUTION') {
              const res = e.data.resolution;
              if (res) {
                currentTargetResolution = res;
                userManualOverride = false; // Backend update overrides prior manual session choice
                const p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (p) {
                  applyPlayerQuality(p, res);
                }
              }
            }
          });

          // Safe Watchdog: ONLY auto-confirms actual YouTube modal dialogs (e.g. "Video paused. Continue watching?")
          // NEVER click .ytp-bezel-text or player UI controls!
          const youtubeWatchdog = () => {
            const p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
            if (p) {
              hookYouTubePlayer(p);

              const confirmBtn = document.querySelector(
                'yt-confirm-dialog-renderer #confirm-button button, ytd-popup-container yt-button-renderer#confirm-button button'
              );
              if (confirmBtn) {
                try { confirmBtn.click(); } catch(e) {}
              }
            }
          };

          setInterval(youtubeWatchdog, 1500);
        } catch(e) {}
      })();
    `;

    const scriptEl = document.createElement('script');
    scriptEl.textContent = shieldCode;
    (document.head || document.documentElement).appendChild(scriptEl);
    scriptEl.remove();
  } catch (e) {}

  // =========================================================================
  // 3. YOUTUBE LOCALSTORAGE INITIAL QUALITY PERSISTENCE
  // =========================================================================
  try {
    localStorage.setItem('yt-player-quality', JSON.stringify({
      data: "small",
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

          // Note: hardwareConcurrency is managed natively via GeckoView's dom.maxHardwareConcurrency
          // Note: deviceMemory is omitted as genuine Firefox/GeckoView does not implement it

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

          // WebRTC Leak Shield: prevents IPv6 candidate leaks and respects webRtcMode
          try {
            const OrigRTCPeerConnection = window.RTCPeerConnection || window.mozRTCPeerConnection || window.webkitRTCPeerConnection;
            if (OrigRTCPeerConnection) {
              const rtcMode = ('${cfg.webRtcMode || "Mdns"}').toLowerCase();
              if (rtcMode === 'disabled') {
                const DisabledPeerConnection = wrapNative(function() {
                  throw new DOMException('RTCPeerConnection is disabled in this profile.', 'NotSupportedError');
                }, 'RTCPeerConnection');
                DisabledPeerConnection.prototype = OrigRTCPeerConnection.prototype;
                window.RTCPeerConnection = DisabledPeerConnection;
                if (window.mozRTCPeerConnection) window.mozRTCPeerConnection = DisabledPeerConnection;
                if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = DisabledPeerConnection;
              } else {
                const sanitizeCandidate = (c) => {
                  if (!c || !c.candidate) return c;
                  const candStr = c.candidate;
                  // If candidate contains an IPv6 address (contains multiple colons), suppress it
                  // so WebRTC never leaks an IPv6 address that contradicts the IPv4 connection
                  const ipv6Regex = /([0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F]{1,4}/;
                  if (ipv6Regex.test(candStr)) {
                    return null;
                  }
                  return c;
                };

                const PatchedRTCPeerConnection = wrapNative(function(config, constraints) {
                  const pc = new OrigRTCPeerConnection(config, constraints);

                  const origAddEventListener = pc.addEventListener;
                  pc.addEventListener = wrapNative(function(type, listener, options) {
                    if (type === 'icecandidate') {
                      const wrappedListener = function(event) {
                        if (event && event.candidate) {
                          const sanitized = sanitizeCandidate(event.candidate);
                          if (!sanitized) return;
                        }
                        return listener.apply(this, arguments);
                      };
                      return origAddEventListener.call(pc, type, wrappedListener, options);
                    }
                    return origAddEventListener.apply(pc, arguments);
                  }, 'addEventListener');

                  let userOnIceCandidate = null;
                  Object.defineProperty(pc, 'onicecandidate', {
                    get: wrapNative(function() { return userOnIceCandidate; }, 'get onicecandidate'),
                    set: wrapNative(function(fn) {
                      userOnIceCandidate = fn;
                      if (!fn) {
                        pc.onicecandidate = null;
                        return;
                      }
                      origAddEventListener.call(pc, 'icecandidate', function(event) {
                        if (event && event.candidate) {
                          const sanitized = sanitizeCandidate(event.candidate);
                          if (!sanitized) return;
                        }
                        if (typeof userOnIceCandidate === 'function') {
                          userOnIceCandidate.call(pc, event);
                        }
                      });
                    }, 'set onicecandidate'),
                    enumerable: true,
                    configurable: true
                  });

                  return pc;
                }, 'RTCPeerConnection');

                PatchedRTCPeerConnection.prototype = OrigRTCPeerConnection.prototype;
                window.RTCPeerConnection = PatchedRTCPeerConnection;
                if (window.mozRTCPeerConnection) window.mozRTCPeerConnection = PatchedRTCPeerConnection;
                if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = PatchedRTCPeerConnection;
              }
            }
          } catch(e) {}
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
