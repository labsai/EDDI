function TestCaseActionHandler(contentBuilder, dataProvider) {
    let instance = this;
    this.observer = new Observer(function (event) {
        switch (event.command) {
//            case 'BotDropDownChanged':
//                if (event.oldValue != event.value) {
//                    let query = $.url.parse(window.location.href);
//
//                    if (typeof query.params === 'undefined') {
//                        query.params = {};
//                    }
//                    if (event.value !== 'All') {
//                        query.params.botId = event.value;
//                    } else {
//                        delete query.params.botId;
//                    }
//                    delete query.params.botVersion;
//                    delete query.query;
//                    delete query.relative;
//                    delete query.source;
//
//                    /*Reload the page with the new version active.*/
//                    window.location.assign($.url.build(query));
//                }
//                break;
            default:
                break;
        }
    });
}