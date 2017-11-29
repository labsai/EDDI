function GenericLifecycleTaskModel(lifecycle) {
    this.type = lifecycle.type;

    var instance = this;
    this.convertToJSON = function () {
        var lifecycleCopy = jQuery.extend(true, {}, lifecycle);

        for (var key in lifecycleCopy.config) {
            lifecycleCopy.config[key] = instance[key];
        }

        if (lifecycleCopy.extensions) {
            for (var extensionKey in lifecycleCopy.extensions) {
                lifecycleCopy.extensions[extensionKey] = [];
                for (var i = 0; i < instance.backingGroupControl.getModel().children.length; ++i) {
                    var gc = instance.backingGroupControl.getModel().children[i];
                    var split = gc.getModel().context.namespace.split('.').last();

                    if (split == extensionKey) {
                        for (var j = 0; j < gc.getModel().children.length; ++j) {
                            lifecycleCopy.extensions[extensionKey].push(gc.getModel().children[j].getModel().convertToJSON());
                        }
                    }
                }
            }
        }

        return lifecycleCopy;
    };

    this.makeDefaultJSON = function () {
        return application.jsonBuilderHelper.makeDefaultJSONFromExtension(this.type);
    };

    if (typeof lifecycle === "undefined" || lifecycle === null) {
        lifecycle = this.makeDefaultJSON();
    }

    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'genericlifecycletask_';
    this.lifecycle = lifecycle;
    this.children = [];

    if (lifecycle.config) {
        for (key in lifecycle.config) {
            this[key] = lifecycle.config[key];
        }
    }
}