function MonitorActionHandler(contentBuilder, dataProvider) {
    let instance = this;
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'CreateTestCase':
                dataProvider.createTestCase(application.dataProvider.dataProviderState.getActiveId());

                let query = $.url.parse(application.url.getUriForPage('testcases'));

                if (typeof query.params === 'undefined') {
                    query.params = {};
                }

                let botId = application.contentModelProvider.getBotId();
                let botVersion = application.contentModelProvider.getBotVersion();

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