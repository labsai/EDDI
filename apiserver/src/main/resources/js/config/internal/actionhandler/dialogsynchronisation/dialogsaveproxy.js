function DialogSaveProxy() {
    this.updateBehaviorRuleSet = function (set, completion) {
        var id = application.dataProvider.dataProviderState.getActiveId();
        var version = application.dataProvider.dataProviderState.getActiveVersion();

        var uri = application.url.getUriForResource(id, version);

        completion(200, {responseText: ""}, uri);
    }

    this.setActiveVersion = function (value) {
        /** NOP */
    }
}