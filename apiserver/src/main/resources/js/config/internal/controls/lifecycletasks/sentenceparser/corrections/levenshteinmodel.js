function LevenshteinModel(lifecycle) {
    let instance = this;
    this.type = "core://ai.labs.parser.corrections.levenshtein?version=1";

    this.convertToJSON = function () {
        return {
            "type": instance.type,
            "config": {
                "distance": instance.distance
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
    this.idPrefix = 'levenshtein_';
    this.distance = lifecycle.config.distance;
    this.lifecycle = lifecycle;

    this.configDefinition = application.jsonBuilderHelper.fetchExtension(this.type).configDefinition;
}