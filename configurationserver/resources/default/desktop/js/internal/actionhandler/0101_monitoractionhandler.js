function MonitorActionHandler(contentBuilder, dataProvider) {
    var instance = this;
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'CreateTestCase':
                dataProvider.createTestCase(application.dataProvider.dataProviderState.getActiveId());

                var query = $.url.parse(application.url.getUriForPage('testcases'));

                if (typeof query.params === 'undefined') {
                    query.params = {};
                }

                var botId = application.contentModelProvider.getBotId();
                var botVersion = application.contentModelProvider.getBotVersion();

                if (typeof botId !== 'undefined') {
                    query.params.botId = botId;
                }
                if (typeof botVersion !== 'undefined') {
                    query.params.botVersion = botVersion;
                }

                delete query.query;
                delete query.relative;
                delete query.source;

                /*Reload the page with the new version active.*/
                window.location.assign($.url.build(query));
                break;
            default:
                break;
        }
    });
}