function IntegerModel(lifecycle) {
    this.type = "core://ai.labs.parser.dictionaries.integer?version=1";

    var instance = this;
    this.convertToJSON = function () {
        return {
            "type":instance.type
        };
    };

    this.makeDefaultJSON = function () {
        return application.jsonBuilderHelper.makeDefaultJSONFromExtension(this.type);
    };

    if (typeof lifecycle === "undefined" || lifecycle === null) {
        lifecycle = this.makeDefaultJSON();
    }

    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'integer_';
    this.lifecycle = lifecycle;
}