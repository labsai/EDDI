function DialogSaveProxy() {
    this.updateBehaviorRuleSet = function (set, completion) {
        let id = application.dataProvider.dataProviderState.getActiveId();
        let version = application.dataProvider.dataProviderState.getActiveVersion();

        let uri = application.url.getUriForResource(id, version);

        completion(200, {responseText: ""}, uri);
    };

    this.setActiveVersion = function (value) {
        /** NOP */
    }
}