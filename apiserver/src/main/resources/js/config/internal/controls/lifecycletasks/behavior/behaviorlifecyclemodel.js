function BehaviorLifecycleModel(lifecycle) {
    this.type = "core://ai.labs.behavior?version=1";
    var instance = this;

    this.convertToJSON = function () {
        var uri;

        if (instance.backingArray.length == 0) {
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

    var behaviorDescriptionsCacheId = 'BEHAVIOR_DESCRIPTIONS';

    var behaviorDescriptions = application.networkCacheManager.cachedNetworkCall(behaviorDescriptionsCacheId,
        application.dataProvider, application.dataProvider.readDocumentDescriptions,
        ['ai.labs.behavior', 0, 0, '', 'asc']
    );

    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'behaviorlifecycle_';
    this.backingArray = behaviorDescriptions;
    this.children = [];
    this.lifecycle = lifecycle;

    console.log('uri is: ' + lifecycle.config.uri);

    var index = -1;
    for (var i = 0; i < this.backingArray.length; ++i) {
        if (this.backingArray[i].resource == lifecycle.config.uri) {

            index = i;
        }
    }

    if (index !== -1) {
        this.selectedItemIndex = index;
    } else {
        this.selectedItemIndex = 0;
    }
}