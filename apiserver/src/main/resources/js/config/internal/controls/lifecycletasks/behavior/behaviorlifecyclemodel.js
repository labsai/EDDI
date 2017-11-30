function BehaviorLifecycleModel(lifecycle) {
    this.type = "core://ai.labs.behavior?version=1";
    let instance = this;

    this.convertToJSON = function () {
        let uri;

        if (instance.backingArray.length === 0) {
            uri = "";
        } else {
            uri = instance.backingArray[instance.children[0].getModel().children[0].getModel().selectedItemIndex].resource;
        }

        return {
            "type": instance.type,
            "config": {
                uri: uri
            }
        };
    };

    this.makeDefaultJSON = function () {
        return application.jsonBuilderHelper.makeDefaultJSONFromExtension(this.type);
    };

    if (typeof lifecycle === "undefined" || lifecycle === null) {
        lifecycle = this.makeDefaultJSON();
    }

    let behaviorDescriptionsCacheId = 'BEHAVIOR_DESCRIPTIONS';

    let behaviorDescriptions = application.networkCacheManager.cachedNetworkCall(behaviorDescriptionsCacheId,
        application.dataProvider, application.dataProvider.readDocumentDescriptions,
        ['ai.labs.behavior', 0, 0, '', 'asc']
    );

    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'behaviorlifecycle_';
    this.backingArray = behaviorDescriptions;
    this.children = [];
    this.lifecycle = lifecycle;

    console.log('uri is: ' + lifecycle.config.uri);

    let index = -1;
    for (let i = 0; i < this.backingArray.length; ++i) {
        if (this.backingArray[i].resource === lifecycle.config.uri) {

            index = i;
        }
    }

    if (index !== -1) {
        this.selectedItemIndex = index;
    } else {
        this.selectedItemIndex = 0;
    }
}