(function () {
    if (window.__axCosmeticAdblock) return;
    window.__axCosmeticAdblock = true;

    // Generic ad container selectors (uBlock-style cosmetic hiding).
    var AD_SELECTORS = [
        'ins.adsbygoogle',
        'iframe[src*="doubleclick.net"]',
        'iframe[src*="googlesyndication"]',
        'iframe[id^="google_ads_"]',
        'iframe[id^="aswift_"]',
        'div[id^="div-gpt-ad"]',
        'div[id*="google_ads"]',
        'div[class*="adsbygoogle"]',
        'div[class^="ad-"]',
        'div[class*="-ad-"]',
        'div[class$="-ad"]',
        'div[id^="ad-"]',
        'div[id$="-ad"]',
        'div[class*="advert"]',
        'div[class*="sponsor"]',
        'aside[class*="ad-"]',
        '[data-ad-slot]',
        '[data-ad-client]',
        '[aria-label="Advertisement"]',
        '.ad-banner', '.ad-container', '.ad-wrapper', '.adsbox', '.adslot',
        '.banner-ads', '.sponsored-content', '.promoted', '.taboola', '.OUTBRAIN'
    ];

    function hideAds(root) {
        try {
            AD_SELECTORS.forEach(function (sel) {
                (root || document).querySelectorAll(sel).forEach(function (el) {
                    if (el && el.style && el.getAttribute('data-ax-hidden') !== '1') {
                        el.style.setProperty('display', 'none', 'important');
                        el.setAttribute('data-ax-hidden', '1');
                    }
                });
            });
        } catch (e) {}
    }

    // YouTube-specific: skip and mute video ads, remove overlays.
    function handleYouTube() {
        var host = location.hostname;
        if (host.indexOf('youtube.com') < 0 && host.indexOf('youtube-nocookie.com') < 0) return;
        try {
            var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
            var isAd = player && player.classList.contains('ad-showing');
            if (isAd) {
                var skip = document.querySelector(
                    '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button'
                );
                if (skip) { skip.click(); }
                var video = document.querySelector('video');
                if (video && !isNaN(video.duration) && video.duration > 0) {
                    video.currentTime = video.duration;   // fast-forward through ad
                    video.muted = true;
                }
            }
            // Remove banner/overlay ads on the watch page.
            [
                '.ytp-ad-overlay-container',
                '.ytp-ad-image-overlay',
                '.ytp-ad-message-container',
                'ytd-promoted-sparkles-web-renderer',
                'ytd-display-ad-renderer',
                'ytd-ad-slot-renderer',
                'ytd-in-feed-ad-layout-renderer',
                '#player-ads',
                '#masthead-ad'
            ].forEach(function (sel) {
                document.querySelectorAll(sel).forEach(function (el) {
                    el.style.setProperty('display', 'none', 'important');
                });
            });
        } catch (e) {}
    }

    function tick() {
        hideAds(document);
        handleYouTube();
    }

    tick();
    // Re-run on DOM mutations (SPA sites inject ads dynamically).
    try {
        var mo = new MutationObserver(function () { tick(); });
        mo.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    // Safety net for video-ad timing.
    setInterval(handleYouTube, 500);
})();
