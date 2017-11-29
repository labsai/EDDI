function RegularModel(lifecycle) {
    this.type = "core://ai.labs.parser.dictionaries.regular?version=1";
    var instance = this;

    this.convertToJSON = function () {
        return {
            "type": instance.type,
            "config": {
                "uri": instance.uri
            }
        };
    };

    this.makeDefaultJSON = function () {
        return application.jsonBuilderHelper.makeDefaultJSONFromExtension(this.type);
    };

    if (typeof lifecycle === "undefined" || lifecycle === null) {
        lifecycle = this.makeDefaultJSON();
    }

    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'regular_';
    this.uri = lifecycle.config.uri;
    this.lifecycle = lifecycle;
    this.configDefinition = application.jsonBuilderHelper.fetchExtension(this.type).configDefinition;
}
