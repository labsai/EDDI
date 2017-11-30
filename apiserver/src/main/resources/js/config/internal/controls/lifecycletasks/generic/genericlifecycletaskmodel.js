function GenericLifecycleTaskModel(lifecycle) {
    this.type = lifecycle.type;

    let instance = this;
    this.convertToJSON = function () {
        let lifecycleCopy = jQuery.extend(true, {}, lifecycle);

        for (let key in lifecycleCopy.config) {
            lifecycleCopy.config[key] = instance[key];
        }

        if (lifecycleCopy.extensions) {
            for (let extensionKey in lifecycleCopy.extensions) {
                lifecycleCopy.extensions[extensionKey] = [];
                for (let i = 0; i < instance.backingGroupControl.getModel().children.length; ++i) {
                    let gc = instance.backingGroupControl.getModel().children[i];
                    let split = gc.getModel().context.namespace.split('.').last();

                    if (split === extensionKey) {
                        for (let j = 0; j < gc.getModel().children.length; ++j) {
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