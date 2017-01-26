function PhoneticModel(lifecycle) {
    this.type = "core://ai.labs.parser.corrections.phonetic?version=1";
    var instance = this;

    this.convertToJSON = function () {
        return {
            "type":instance.type,
            "config":{
                "metaphone":instance.metaphone,
                "soundex":instance.soundex
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
    this.idPrefix = 'phonetic_';
    this.metaphone = lifecycle.config.metaphone;
    this.soundex = lifecycle.config.soundex;
    this.lifecycle = lifecycle;
    this.configDefinition = application.jsonBuilderHelper.fetchExtension(this.type).configDefinition;
}