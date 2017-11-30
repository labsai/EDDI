function MonitorDescriptionsActionHandler(contentBuilder, dataProvider) {
    let instance = this;
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'BotDropDownChanged':
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    if (event.value !== 'All') {
                        query.params.botId = event.value;
                    } else {
                        delete query.params.botId;
                    }
                    delete query.params.botVersion;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'BotVersionDropDownChanged':
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    if (event.value !== 'All') {
                        query.params.botVersion = event.value;
                    } else {
                        delete query.params.botVersion;
                    }
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'ConversationStateDropDownChanged':
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    if (event.value !== 'All') {
                        query.params.conversationState = event.value;
                    } else {
                        delete query.params.conversationState;
                    }
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'ViewStateDropDownChanged':
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    if (event.value !== 'All') {
                        query.params.viewState = event.value;
                    } else {
                        delete query.params.viewState;
                    }
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'LimitChanged':
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    query.params.limit = event.value;
                    delete query.params.index;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'IndexChanged':
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    query.params.index = event.value;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'SearchSelected':
                if (typeof event.value !== 'undefined' && event.value.length >= 0) {
                    let query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }

                    if (event.value.length > 0) {
                        query.params.filter = event.value;
                    } else {
                        delete query.params.filter;
                    }
                    delete query.params.index;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            default:
                break;
        }
    });
}