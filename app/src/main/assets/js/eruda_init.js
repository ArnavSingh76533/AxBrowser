(function () {
    if (typeof eruda === 'undefined') return;
    if (!window.__axErudaInit) {
        window.__axErudaInit = true;
        eruda.init({
            tool: ['console', 'elements', 'network', 'resources', 'sources', 'info'],
            useShadowDom: true,
            autoScale: true,
            defaults: {
                displaySize: 60,
                transparency: 0.95,
                theme: 'Dark'
            }
        });
    }
})();
