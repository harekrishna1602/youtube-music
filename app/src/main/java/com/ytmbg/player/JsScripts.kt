package com.ytmbg.player

object JsScripts {

    /**
     * Injected at document_start via WebViewCompat.addDocumentStartJavaScript BEFORE
     * YouTube Music's own scripts initialize:
     *  - Page Visibility API spoof (keeps screen-off playback alive from frame 0)
     *  - Layer 2 Ad Blocker: Prunes adPlacements, playerAds, and adSlots from
     *    window.ytInitialPlayerResponse, fetch(), and XMLHttpRequest responses.
     */
    val DOCUMENT_START_SCRIPT: String = """
        (function() {
            if (window.__ytmBgDocStartInstalled) return;
            window.__ytmBgDocStartInstalled = true;

            // --- 1. Page Visibility API Spoof (Screen-off playback) ---
            try {
                Object.defineProperty(document, 'hidden', {
                    get: function() { return false; },
                    configurable: true
                });
                Object.defineProperty(document, 'webkitHidden', {
                    get: function() { return false; },
                    configurable: true
                });
                Object.defineProperty(document, 'visibilityState', {
                    get: function() { return 'visible'; },
                    configurable: true
                });
                Object.defineProperty(document, 'webkitVisibilityState', {
                    get: function() { return 'visible'; },
                    configurable: true
                });
            } catch (e) {}

            var blockedEvents = ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'];
            blockedEvents.forEach(function(evtName) {
                window.addEventListener(evtName, function(e) {
                    e.stopImmediatePropagation();
                }, true);
                document.addEventListener(evtName, function(e) {
                    e.stopImmediatePropagation();
                }, true);
            });

            // --- 2. Layer 2 Ad Blocker: JSON Response Pruning ---
            var AD_KEYS = [
                'adPlacements',
                'playerAds',
                'adSlots',
                'adBreakHeartbeatParams'
            ];

            function stripAds(obj) {
                if (!obj || typeof obj !== 'object') return obj;
                try {
                    for (var i = 0; i < AD_KEYS.length; i++) {
                        if (AD_KEYS[i] in obj) {
                            delete obj[AD_KEYS[i]];
                        }
                    }
                    if (obj.playerResponse && typeof obj.playerResponse === 'object') {
                        stripAds(obj.playerResponse);
                    }
                    if (Array.isArray(obj)) {
                        for (var j = 0; j < obj.length; j++) {
                            if (obj[j] && typeof obj[j] === 'object' && obj[j].playerResponse) {
                                stripAds(obj[j].playerResponse);
                            }
                        }
                    }
                } catch (e) {}
                return obj;
            }

            function isTargetApiUrl(url) {
                if (!url || typeof url !== 'string') return false;
                return url.indexOf('/youtubei/v1/player') !== -1 ||
                       url.indexOf('/youtubei/v1/next') !== -1;
            }

            // 2a. Intercept window.ytInitialPlayerResponse on initial page load
            try {
                var initialPlayerResp = window.ytInitialPlayerResponse
                    ? stripAds(window.ytInitialPlayerResponse)
                    : undefined;
                Object.defineProperty(window, 'ytInitialPlayerResponse', {
                    configurable: true,
                    get: function() { return initialPlayerResp; },
                    set: function(val) { initialPlayerResp = stripAds(val); }
                });
            } catch (e) {}

            // 2b. Intercept fetch() calls to /youtubei/v1/player and /youtubei/v1/next
            if (typeof window.fetch === 'function') {
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = '';
                    try {
                        url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                    } catch (e) {}

                    var fetchPromise = origFetch.apply(this, arguments);
                    if (!isTargetApiUrl(url)) {
                        return fetchPromise;
                    }

                    return fetchPromise.then(function(response) {
                        if (!response || !response.ok) return response;
                        var cloned = response.clone();
                        return cloned.json().then(function(data) {
                            stripAds(data);
                            var headers = new Headers(response.headers);
                            headers.delete('content-length');
                            return new Response(JSON.stringify(data), {
                                status: response.status,
                                statusText: response.statusText,
                                headers: headers
                            });
                        }).catch(function() {
                            return response;
                        });
                    });
                };
            }

            // 2c. Intercept XMLHttpRequest fallback for /youtubei/v1/player and /youtubei/v1/next
            if (typeof window.XMLHttpRequest === 'function') {
                var origOpen = XMLHttpRequest.prototype.open;
                var responseTextDesc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
                var responseDesc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');

                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__ytmBgUrl = typeof url === 'string' ? url : '';
                    return origOpen.apply(this, arguments);
                };

                if (responseTextDesc && responseTextDesc.get) {
                    Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
                        configurable: true,
                        get: function() {
                            var raw = responseTextDesc.get.call(this);
                            if (this.readyState === 4 && isTargetApiUrl(this.__ytmBgUrl) && raw) {
                                try {
                                    var parsed = JSON.parse(raw);
                                    stripAds(parsed);
                                    return JSON.stringify(parsed);
                                } catch (e) {}
                            }
                            return raw;
                        }
                    });
                }

                if (responseDesc && responseDesc.get) {
                    Object.defineProperty(XMLHttpRequest.prototype, 'response', {
                        configurable: true,
                        get: function() {
                            var raw = responseDesc.get.call(this);
                            if (this.readyState === 4 && isTargetApiUrl(this.__ytmBgUrl) && raw) {
                                try {
                                    if (typeof raw === 'string') {
                                        var parsedStr = JSON.parse(raw);
                                        stripAds(parsedStr);
                                        return JSON.stringify(parsedStr);
                                    } else if (typeof raw === 'object') {
                                        return stripAds(raw);
                                    }
                                } catch (e) {}
                            }
                            return raw;
                        }
                    });
                }
            }
        })();
    """.trimIndent()

    /**
     * Injected on page load (onPageStarted / onPageFinished):
     *  - Layer 3 Ad Blocker: Cosmetic CSS hiding + MutationObserver auto-mute/skip fallback
     *  - Anti-idle ("Still listening?") auto-dismissal
     *  - Lock-screen / Bluetooth remote control bridge
     *  - Metadata sync to Android Foreground Service (suppressed during ads)
     */
    val RUNTIME_AND_COSMETIC_SCRIPT: String = """
        (function() {
            if (window.__ytmBgRuntimeInstalled) return;
            window.__ytmBgRuntimeInstalled = true;

            // --- 1. Layer 3a: Cosmetic CSS Hiding (Banners, Overlays, Premium Promos) ---
            function injectCosmeticCss() {
                try {
                    if (document.getElementById('ytm-bg-adblock-css')) return;
                    var style = document.createElement('style');
                    style.id = 'ytm-bg-adblock-css';
                    style.textContent = [
                        'ytmusic-mealbar-promo-renderer,',
                        'ytmusic-statement-banner-renderer,',
                        'ytmusic-promoted-sparkles-web-renderer,',
                        'ytd-ad-slot-renderer,',
                        'ytd-promoted-sparkles-web-renderer,',
                        '.ytp-ad-overlay-container,',
                        '.ytp-ad-module,',
                        '.ytp-ad-text-overlay,',
                        '.ytp-ad-image-overlay,',
                        '#masthead-ad,',
                        '.advertisement {',
                        '    display: none !important;',
                        '    visibility: hidden !important;',
                        '    height: 0 !important;',
                        '    min-height: 0 !important;',
                        '    pointer-events: none !important;',
                        '}'
                    ].join('\n');
                    (document.head || document.documentElement).appendChild(style);
                } catch (e) {}
            }
            injectCosmeticCss();

            // --- 2. Layer 3b: Playback Fallback (Auto-Mute + Instant Seek + Auto-Skip) ---
            var adMuteActive = false;
            var wasMutedBeforeAd = false;

            function isAdPlaying() {
                try {
                    var player = document.querySelector('.html5-video-player, #movie_player');
                    if (!player) return false;
                    return player.classList.contains('ad-showing') ||
                           player.classList.contains('ad-interrupting');
                } catch (e) {
                    return false;
                }
            }

            function handleFallbackAd() {
                try {
                    var video = document.querySelector('video');
                    if (isAdPlaying()) {
                        if (video) {
                            if (!adMuteActive) {
                                wasMutedBeforeAd = !!video.muted;
                                adMuteActive = true;
                            }
                            video.muted = true;
                            if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.1) {
                                video.currentTime = video.duration;
                            }
                        }

                        var skipSelectors = [
                            '.ytp-skip-ad-button',
                            '.ytp-ad-skip-button',
                            '.ytp-ad-skip-button-modern',
                            '.ytp-ad-skip-button-slot button',
                            '.ytp-ad-overlay-close-button'
                        ];
                        for (var i = 0; i < skipSelectors.length; i++) {
                            var btn = document.querySelector(skipSelectors[i]);
                            if (btn && btn.offsetParent !== null) {
                                btn.click();
                            }
                        }
                    } else if (adMuteActive) {
                        if (video) {
                            video.muted = wasMutedBeforeAd;
                        }
                        adMuteActive = false;
                    }
                } catch (e) {}
            }

            function startAdObserver() {
                try {
                    if (!document.body) return;
                    var observer = new MutationObserver(function() {
                        handleFallbackAd();
                    });
                    observer.observe(document.body, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['class']
                    });
                } catch (e) {}
            }

            if (document.body) {
                startAdObserver();
            } else {
                document.addEventListener('DOMContentLoaded', function() {
                    injectCosmeticCss();
                    startAdObserver();
                });
            }
            setInterval(handleFallbackAd, 400);

            // --- 3. Prevent "Still listening?" Idle Timeout ---
            setInterval(function() {
                try {
                    window._lact = Date.now();
                    var confirmBtn = document.querySelector('ytmusic-you-there-renderer yt-button-renderer button, tp-yt-paper-dialog yt-button-renderer.ytmusic-you-there-renderer button');
                    if (confirmBtn && confirmBtn.offsetParent !== null) {
                        confirmBtn.click();
                    }
                } catch (e) {}
            }, 45000);

            // --- 4. Remote Control Bridge (Lock-screen & Bluetooth) ---
            window.__ytmBgControl = function(action) {
                try {
                    var video = document.querySelector('video');
                    var playPauseBtn = document.querySelector('#play-pause-button, .play-pause-button');
                    var nextBtn = document.querySelector('.next-button');
                    var prevBtn = document.querySelector('.previous-button');

                    if (action === 'play') {
                        if (video && video.paused) {
                            if (playPauseBtn) playPauseBtn.click();
                            else video.play();
                        }
                    } else if (action === 'pause') {
                        if (video && !video.paused) {
                            if (playPauseBtn) playPauseBtn.click();
                            else video.pause();
                        }
                    } else if (action === 'toggle') {
                        if (playPauseBtn) {
                            playPauseBtn.click();
                        } else if (video) {
                            if (video.paused) video.play();
                            else video.pause();
                        }
                    } else if (action === 'next') {
                        if (nextBtn) nextBtn.click();
                    } else if (action === 'prev') {
                        if (prevBtn) prevBtn.click();
                    }
                } catch (e) {}
            };

            // --- 5. Sync Playback State & Track Metadata (Ignores Ad Metadata) ---
            var lastState = '';
            function syncPlaybackState() {
                try {
                    if (!window.AndroidBridge || !window.AndroidBridge.onPlaybackStateChanged) return;
                    // Never flash ad titles onto the Android lock screen
                    if (isAdPlaying()) return;

                    var video = document.querySelector('video');
                    var isPlaying = !!(video && !video.paused && !video.ended && video.readyState > 1);

                    var title = '';
                    var artist = '';

                    if (navigator.mediaSession && navigator.mediaSession.metadata) {
                        title = navigator.mediaSession.metadata.title || '';
                        artist = navigator.mediaSession.metadata.artist || '';
                    }

                    if (!title) {
                        var titleEl = document.querySelector('ytmusic-player-bar .title');
                        if (titleEl) title = (titleEl.textContent || '').trim();
                    }
                    if (!artist) {
                        var bylineEl = document.querySelector('ytmusic-player-bar .byline');
                        if (bylineEl) artist = (bylineEl.textContent || '').trim();
                    }

                    if (!title) title = 'YouTube Music';
                    if (!artist) artist = isPlaying ? 'Playing in background' : 'Paused';

                    var signature = isPlaying + '|' + title + '|' + artist;
                    if (signature !== lastState) {
                        lastState = signature;
                        window.AndroidBridge.onPlaybackStateChanged(isPlaying, title, artist);
                    }
                } catch (e) {}
            }

            setInterval(syncPlaybackState, 1500);
            document.addEventListener('play', syncPlaybackState, true);
            document.addEventListener('pause', syncPlaybackState, true);
            document.addEventListener('loadedmetadata', syncPlaybackState, true);
        })();
    """.trimIndent()
}
