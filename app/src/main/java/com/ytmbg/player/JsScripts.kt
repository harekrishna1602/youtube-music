package com.ytmbg.player

object JsScripts {

    val DOCUMENT_START_SCRIPT: String = """
        (function() {
            var host = (location && location.hostname ? location.hostname : '').toLowerCase();
            if (host.indexOf('youtube.com') === -1) return;
            if (window.__ytmBgDocStartInstalled) return;
            window.__ytmBgDocStartInstalled = true;

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

    val RUNTIME_AND_COSMETIC_SCRIPT: String = """
        (function() {
            var host = (location && location.hostname ? location.hostname : '').toLowerCase();
            if (host.indexOf('youtube.com') === -1) return;
            if (window.__ytmBgRuntimeInstalled) return;
            window.__ytmBgRuntimeInstalled = true;

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

            setInterval(function() {
                try {
                    window._lact = Date.now();
                    var confirmBtn = document.querySelector('ytmusic-you-there-renderer yt-button-renderer button, tp-yt-paper-dialog yt-button-renderer.ytmusic-you-there-renderer button');
                    if (confirmBtn && confirmBtn.offsetParent !== null) {
                        confirmBtn.click();
                    }
                } catch (e) {}
            }, 45000);

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

            var lastState = '';
            function syncPlaybackState() {
                try {
                    if (!window.AndroidBridge || !window.AndroidBridge.onPlaybackStateChanged) return;
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
