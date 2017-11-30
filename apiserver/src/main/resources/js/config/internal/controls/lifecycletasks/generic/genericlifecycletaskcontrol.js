function GenericLifecycleTaskControl(model) {
    let groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';
    let groupControlBuilder = new GroupControlBuilder();
    let blockControlPrefix = 'config_';


    let getLifeCycleTaskPlugin = function (lifecycleTaskType) {
        if (application.pluginManager.plugins.lifecycletaskhandlers.hasOwnProperty(lifecycleTaskType)) {
            return application.pluginManager.plugins.lifecycletaskhandlers[lifecycleTaskType];
        } else {
            throw new UnknownLifecycleTaskException('No lifecycle task for type: ' + lifecycleTaskType);
        }
    };

    let getLifecycleTaskControl = function (extension) {
        let lifeCycle;
        let lifeCycleModel;
        try {
            lifeCycle = getLifeCycleTaskPlugin(extension.type);
            lifeCycleModel = new lifeCycle.model();
        } catch (ex) {
            if (ex instanceof UnknownLifecycleTaskException) {
                lifeCycle = {model: GenericLifecycleTaskModel, control: GenericLifecycleTaskControl};
                lifeCycleModel = new lifeCycle.model(extension);
            }
        }

        let lifeCycleControl = new lifeCycle.control(lifeCycleModel);

        if (lifeCycleControl.hasOwnProperty('observable')) {
            lifeCycleControl.observable.addObserver(application.actionHandler.observer);
        }

        return lifeCycleControl;
    };

    let displayName;
    let definitionNamespace = model.type.split('//')[1].split('?')[0];
    let definitionCacheId = 'CONTROL_DEFINITION_CACHE_' + definitionNamespace;
    let definition = application.networkCacheManager.cachedNetworkCall(definitionCacheId, application.dataProvider,
        application.dataProvider.readExtensionDefinitions,
        [definitionNamespace])[0];

    if (definition.name === "") {
        displayName = model.type;
    } else {
        displayName = definition.name;
    }

    let groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        displayName, true, true, false, 'packagecontrol', false);

    let instance = this;
    /** Configure the BehaviorLifecycleControl to act as a transparent proxy for its backing groupControl / blockControl. */
    this.observer = new Observer(function (event) {
        if (event.command === 'UpdatedModel') {
            let htmlId = '#' + model.backingGroupControl.getModel().idPrefix + model.backingGroupControl.getModel().id;

            $(htmlId).removeClass(application.configuration.newStateClassName);
            model.backingGroupControl.getModel().removeClass(application.configuration.newStateClassName);
            $(htmlId).addClass(application.configuration.editedStateClassName);
            model.backingGroupControl.getModel().addClass(application.configuration.editedStateClassName);
            application.reloadManager.changesHappened();
        } else {
            let newEvent = jQuery.extend(true, {}, event);
            newEvent.sender = instance;
            instance.observable.notify(newEvent);
        }
    });

    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

    if (model.lifecycle.config && ObjectUtils.prototype.getNumberOfProperties(model.lifecycle.config) > 0) {
        let blockModel = new BlockControlModel(model.id, model.idPrefix + blockControlPrefix, 'blockcontrol',
            window.lang.convert('LIFECYCLE_CONFIG'), true, false, model.type, this);
        let blockControl = new BlockControl(blockModel);

        blockControl.observable.addObserver(this.observer);

        groupControl.getModel().addChild(blockControl);
    }

    let extensionPoints = application.jsonBuilderHelper.fetchExtension(model.lifecycle.type).extensionPoints;

    if (extensionPoints && ObjectUtils.prototype.getNumberOfProperties(extensionPoints) > 0) {
        let groupControlBuilder = new GroupControlBuilder();

        for (let i = 0; i < extensionPoints.length; ++i) {
            let extensionPoint = extensionPoints[i];

            let extensionGC = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix +
                i + '_',
                window.lang.convert(extensionPoint.displayKey), false, false, true, 'packagecontrol', true);

            extensionGC.getModel().context = {namespace: extensionPoint.namespace.split('//')[1]};

            let extensions = model.lifecycle.extensions[extensionPoint.namespace.split(model.type + '.').last()];

            console.log(window.lang.convert(extensionPoint.displayKey) + '::' + extensionGC.getModel().context.namespace)
            if (extensions) {
                for (let j = 0; j < extensions.length; ++j) {
                    let extension = extensions[j];

                    let pluginControl = getLifecycleTaskControl(extension);

                    extensionGC.getModel().addChild(pluginControl);
                }
            }

            groupControl.getModel().addChild(extensionGC);
        }
    }

    model.backingGroupControl = groupControl;
    model.children.push(groupControl);

    this.createRepresentation = function () {
        return '<div id="' + model.idPrefix + model.id + '">' + groupControl.createRepresentation() + '<div class="clear"></div></div>';
    };

    this.getModel = function () {
        return model;
    };

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    };

    this.registerButtonEvents = function () {
        groupControl.registerButtonEvents();
    }
}