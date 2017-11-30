function NavigationManager() {
    this.observable = new Observable();

    function MenuItem(name, link) {
        this.name = name;
        this.link = link;
    }

    this.initialize = function () {
        this.loadMainMenu();
    };

    this.getMainMenu = function () {
        let config = [];

        config.push(new MenuItem(window.lang.convert("NAVIGATION_BOTS"), application.url.getUriForPage('bots')));
        config.push(new MenuItem(window.lang.convert("NAVIGATION_PACKAGES"), application.url.getUriForPage('packages')));
        config.push(new MenuItem(window.lang.convert("NAVIGATION_DICTIONARIES"), application.url.getUriForPage('dictionaries')));
        config.push(new MenuItem(window.lang.convert("NAVIGATION_DIALOGS"), application.url.getUriForPage('dialogs')));
        config.push(new MenuItem(window.lang.convert("NAVIGATION_OUTPUTS"), application.url.getUriForPage('outputs')));
        config.push(new MenuItem(window.lang.convert("NAVIGATION_MONITOR"), application.url.getUriForPage('monitor')));
//        config.push(new MenuItem(window.lang.convert("NAVIGATION_PROPERTIES"), application.url.getUriForPage('properties')));
//        config.push(new MenuItem(window.lang.convert("NAVIGATION_TESTING"), application.url.getUriForPage('bots')));
//        config.push(new MenuItem(window.lang.convert("NAVIGATION_SETTINGS"), application.url.getUriForPage('bots')));

        return config;
    };

    this.loadMainMenu = function () {
        let config = this.getMainMenu();

        let html = '';

        for (let i = 0; i < config.length; i++) {
            let name = config[i].name;
            let link = config[i].link;

            html += '<li><a href="' + link + '">' + name + '</a></li>';
        }

        $('#navigationlist').html(html);
    }
}