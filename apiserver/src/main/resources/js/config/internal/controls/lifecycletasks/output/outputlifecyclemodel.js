function OutputLifecycleModel(lifecycle) {
    this.type = "core://ai.labs.output?version=1"
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

    var outputDescriptionsCacheId = 'OUTPUT_DESCRIPTIONS';

    var outputDescriptions = application.networkCacheManager.cachedNetworkCall(outputDescriptionsCacheId,
        application.dataProvider, application.dataProvider.readDocumentDescriptions,
        ['ai.labs.output', 0, 0, '', 'asc']
    );

    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'outputlifecycle_';
    this.backingArray = outputDescriptions;
    this.children = [];
    this.lifecycle = lifecycle;

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