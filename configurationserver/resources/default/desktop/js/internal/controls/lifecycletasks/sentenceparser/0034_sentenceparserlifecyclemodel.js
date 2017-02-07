function SentenceparserLifecycleModel(lifecycle) {
    this.type = "core://ai.labs.parser?version=1";
    var instance = this;

    this.convertToJSON = function () {
        var dictArray = [];
        var corrArray = [];

        /** Corrections are added first */
        for (var i = 0; i < this.backingGroupControl.getModel().children[0].getModel().children.length; ++i) {
            var correction = this.backingGroupControl.getModel().children[0].getModel().children[i];
            corrArray.push(correction.getModel().convertToJSON());
        }

        /** Dictionaries */
        for (var i = 0; i < this.backingGroupControl.getModel().children[1].getModel().children.length; ++i) {
            var dictionary = this.backingGroupControl.getModel().children[1].getModel().children[i];
            dictArray.push(dictionary.getModel().convertToJSON());
        }

        return {
            type:instance.type,
            "extensions":{
                "dictionaries":dictArray,
                "corrections":corrArray
            }
        }
    };

    this.makeDefaultJSON = function () {
        return application.jsonBuilderHelper.makeDefaultJSONFromExtension(this.type);
    };

    if (typeof lifecycle === "undefined" || lifecycle === null) {
        lifecycle = this.makeDefaultJSON();
    }

    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'sentenceparserlifecycle_';
    this.correction = lifecycle.extensions.corrections;
    this.dictionaries = lifecycle.extensions.dictionaries;
    this.children = [];
    this.lifecycle = lifecycle;
}