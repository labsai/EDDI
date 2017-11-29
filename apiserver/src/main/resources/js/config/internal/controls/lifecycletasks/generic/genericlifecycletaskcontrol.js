function GenericLifecycleTaskControl(model) {
    var groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';
    var groupControlBuilder = new GroupControlBuilder();
    var blockControlPrefix = 'config_';


    var getLifeCycleTaskPlugin = function (lifecycleTaskType) {
        if (application.pluginManager.plugins.lifecycletaskhandlers.hasOwnProperty(lifecycleTaskType)) {
            return application.pluginManager.plugins.lifecycletaskhandlers[lifecycleTaskType];
        } else {
            throw new UnknownLifecycleTaskException('No lifecycle task for type: ' + lifecycleTaskType);
        }
    }

    var getLifecycleTaskControl = function (extension) {
        var lifeCycle;
        var lifeCycleModel;
        try {
            lifeCycle = getLifeCycleTaskPlugin(extension.type);
            lifeCycleModel = new lifeCycle.model();
        } catch (ex) {
            if (ex instanceof UnknownLifecycleTaskException) {
                lifeCycle = {model: GenericLifecycleTaskModel, control: GenericLifecycleTaskControl};
                lifeCycleModel = new lifeCycle.model(extension);
            }
        }

        var lifeCycleControl = new lifeCycle.control(lifeCycleModel);

        if (lifeCycleControl.hasOwnProperty('observable')) {
            lifeCycleControl.observable.addObserver(application.actionHandler.observer);
        }

        return lifeCycleControl;
    };

    var displayName;
    var definitionNamespace = model.type.split('//')[1].split('?')[0];
    var definitionCacheId = 'CONTROL_DEFINITION_CACHE_' + definitionNamespace;
    var definition = application.networkCacheManager.cachedNetworkCall(definitionCacheId, application.dataProvider,
        application.dataProvider.readExtensionDefinitions,
        [definitionNamespace])[0];

    if (definition.name == "") {
        displayName = model.type;
    } else {
        displayName = definition.name;
    }

    var groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        displayName, true, true, false, 'packagecontrol', false);

    var instance = this;
    /** Configure the BehaviorLifecycleControl to act as a transparent proxy for its backing groupControl / blockControl. */
    this.observer = new Observer(function (event) {
        if (event.command == 'UpdatedModel') {
            var htmlId = '#' + model.backingGroupControl.getModel().idPrefix + model.backingGroupControl.getModel().id;

            $(htmlId).removeClass(application.configuration.newStateClassName);
            model.backingGroupControl.getModel().removeClass(application.configuration.newStateClassName);
            $(htmlId).addClass(application.configuration.editedStateClassName);
            model.backingGroupControl.getModel().addClass(application.configuration.editedStateClassName);
            application.reloadManager.changesHappened();
        } else {
            var newEvent = jQuery.extend(true, {}, event);
            newEvent.sender = instance;
            instance.observable.notify(newEvent);
        }
    });

    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

    if (model.lifecycle.config && ObjectUtils.prototype.getNumberOfProperties(model.lifecycle.config) > 0) {
        var blockModel = new BlockControlModel(model.id, model.idPrefix + blockControlPrefix, 'blockcontrol',
            window.lang.convert('LIFECYCLE_CONFIG'), true, false, model.type, this);
        var blockControl = new BlockControl(blockModel);

        blockControl.observable.addObserver(this.observer);

        groupControl.getModel().addChild(blockControl);
    }

    var extensionPoints = application.jsonBuilderHelper.fetchExtension(model.lifecycle.type).extensionPoints;

    if (extensionPoints && ObjectUtils.prototype.getNumberOfProperties(extensionPoints) > 0) {
        var groupControlBuilder = new GroupControlBuilder();

        for (var i = 0; i < extensionPoints.length; ++i) {
            var extensionPoint = extensionPoints[i];

            var extensionGC = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix +
                i + '_',
                window.lang.convert(extensionPoint.displayKey), false, false, true, 'packagecontrol', true);

            extensionGC.getModel().context = {namespace: extensionPoint.namespace.split('//')[1]};

            var extensions = model.lifecycle.extensions[extensionPoint.namespace.split(model.type + '.').last()];

            console.log(window.lang.convert(extensionPoint.displayKey) + '::' + extensionGC.getModel().context.namespace)
            if (extensions) {
                for (var j = 0; j < extensions.length; ++j) {
                    var extension = extensions[j];

                    var pluginControl = getLifecycleTaskControl(extension);

                    extensionGC.getModel().addChild(pluginControl);
                }
            }

            groupControl.getModel().addChild(extensionGC);
        }
    }

    model.backingGroupControl = groupControl;
    model.children.push(groupControl);

    this.createRepresentation = function () {
        var representation = '<div id="' + model.idPrefix + model.id + '">' + groupControl.createRepresentation() + '<div class="clear"></div></div>';

        return representation;
    }

    this.getModel = function () {
        return model;
    }

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    }

    this.registerButtonEvents = function () {
        groupControl.registerButtonEvents();
    }
}