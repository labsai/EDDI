function UnknownLifecycleTaskException(msg) {
    this.message = msg;
}

function PackageContentModel(dataProvider, actionHandler) {
    let firstLevelGroupControlIdPrefix = 'package_';
    let instance = this;

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        let models = [];

        let packageDescription = dataProvider.readActivePackage();
        console.log(packageDescription);

        let description = dataProvider.readActiveDocumentDescription();
        packageDescription.name = description.name;

        application.contentModelHelper.createResourceVersionSelectorControl();
        application.contentModelHelper.createDocumentDescriptorDisplayControl();
        application.contentModelHelper.createReturnToParentButton();
        application.contentModelHelper.createLanguageSelector();

        let packageModel = this.createPackageGroupControlModel(packageDescription);
        let packageControl = this.createGroupControl(packageModel, 'groupcontrol', sizeCallbackInstance, selectionCallbackInstance);

        for (let i = 0; i < packageDescription.packageExtensions.length; ++i) {
            let lifeCycleJSON = packageDescription.packageExtensions[i];

            let lifeCycle;
            try {
                lifeCycle = this.getLifeCycleTaskPlugin(lifeCycleJSON.type);
            } catch (ex) {
                if (ex instanceof UnknownLifecycleTaskException) {
                    /** Add unknown lifecycle tasks as generics. */
                    lifeCycle = {model: GenericLifecycleTaskModel, control: GenericLifecycleTaskControl};
                } else {
                    /** Propagate other exceptions. */
                    throw ex;
                }
            }

            let lifeCycleModel = new lifeCycle.model(lifeCycleJSON);
            let lifeCycleControl = new lifeCycle.control(lifeCycleModel);

            if (lifeCycleControl.hasOwnProperty('observable')) {
                lifeCycleControl.observable.addObserver(application.actionHandler.observer);
            }

            packageModel.addChild(lifeCycleControl);
        }

        models.push(packageControl);

        return models;
    };

    this.createPackageGroupControlModel = function (packageDescription) {
        let footerControls = [];

        let packageId = dataProvider.getNextIdGlobal();

        let footerModel = new FooterControlModel(packageId, firstLevelGroupControlIdPrefix + 'footer_', true);
        let footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        let headerControls = [];

        let retVal = new GroupControlModel(packageId, firstLevelGroupControlIdPrefix, packageDescription.name,
            footerControls, false, true, null, false, false);

        retVal.headerControls = headerControls;
        retVal.context = {namespace: 'ai.labs'};

        return retVal;
    };

    this.createGroupControl = function (model, CSSClassBase, sizeCallbackInstance, selectionCallbackInstance) {
        let gc = new GroupControl(model, CSSClassBase);

        gc.observable.addObserver(sizeCallbackInstance);
        //gc.observable.addObserver(selectionCallbackInstance);
        gc.observable.addObserver(actionHandler.observer);

        return gc;
    };

    this.getLifeCycleTaskPlugin = function (lifecycleTaskType) {
        if (application.pluginManager.plugins.lifecycletaskhandlers.hasOwnProperty(lifecycleTaskType)) {
            return application.pluginManager.plugins.lifecycletaskhandlers[lifecycleTaskType];
        } else {
            throw new UnknownLifecycleTaskException('No lifecycle task for type: ' + lifecycleTaskType);
        }
    };

    let addControl = function (parentControl, control) {
        let ownId = '#' + control.getModel().idPrefix + control.getModel().id;
        let parentId = '#' + application.configuration.referencePrefix + parentControl.getModel().idPrefix + parentControl.getModel().id;

        $(control.createRepresentation())
            .hide()
            .appendTo(parentId)
            .fadeIn();

        parentControl.getModel().addChild(control);
        application.contentBuilder.readjustSize();

        control.registerButtonEvents();

        $(ownId).children().eq(0).addClass(application.configuration.newStateClassName);
        control.getModel().backingGroupControl.getModel().addClass(application.configuration.newStateClassName);
        application.reloadManager.changesHappened();

        if ($(ownId).offset().top - $('#header').outerHeight() > $('#content').outerHeight()) {
            $('#content').stop().animate({
                scrollTop: $(ownId).offset().top - $('#header').outerHeight() - $('#content').outerHeight() / 2 + $(ownId).outerHeight() / 2 + $('#content').scrollTop()
            }, 2000);
        }


        /** From: http://stackoverflow.com/questions/2834667/how-can-i-differentiate-a-manual-scroll-via-mousewheel-scrollbar-from-a-javasc */
        $('#content').bind("scroll mousedown DOMMouseScroll mousewheel keyup", function (e) {
            if (e.which > 0 || e.type === "mousedown" || e.type === "mousewheel") {
                $('#content').stop().unbind('scroll mousedown DOMMouseScroll mousewheel keyup'); // This identifies the scroll as a user action, stops the animation, then unbinds the event straight after (optional)
            }
        });
    };

    this.addChildControl = function (sender) {
        let callback = function (success, event) {
            if (success && event.documentDescription) {
                let value = application.url.getVersionAndIdForResource(event.documentDescription.resource);
                let id = value.id;
                let version = value.version;

                let extensionIdVersionIdentifier = "CACHED_EXTENSION_IDV_" + version + '_' + id;
                let extension = application.networkCacheManager.cachedNetworkCall(extensionIdVersionIdentifier,
                    application.dataProvider,
                    application.dataProvider.readExtension,
                    [id, version]
                );

                let lifeCycle;
                let lifeCycleModel;
                try {
                    lifeCycle = instance.getLifeCycleTaskPlugin(extension.type + '?version=' + version);
                    lifeCycleModel = new lifeCycle.model();
                } catch (ex) {
                    if (ex instanceof UnknownLifecycleTaskException) {
                        /**let errorModel = new DialogControlModel(window.lang.convert("PLUGIN_NOT_FOUND"), function() {},
                         window.lang.convert('OK_BUTTON'), false);
                         let errorControl = new DialogControl(errorModel);
                         errorControl.showDialog();
                         return;*/
                        lifeCycle = {model: GenericLifecycleTaskModel, control: GenericLifecycleTaskControl};
                        lifeCycleModel = new lifeCycle.model(application.jsonBuilderHelper.makeDefaultJSONFromExtension(extension.type));
                    }
                }

                let lifeCycleControl = new lifeCycle.control(lifeCycleModel);

                if (lifeCycleControl.hasOwnProperty('observable')) {
                    lifeCycleControl.observable.addObserver(application.actionHandler.observer);
                }

                addControl(sender, lifeCycleControl);
            }
        };

        let namespaceId = 'CACHED_NAMESPACE_' + sender.getModel().context.namespace;
        let selectionOptions = application.networkCacheManager.cachedNetworkCall(namespaceId, application.dataProvider,
            application.dataProvider.readExtensionDefinitions,
            [sender.getModel().context.namespace]);
        console.log('Options in namespace ' + sender.getModel().context.namespace + ' are: ');
        console.log(selectionOptions);

        let unnamedCounter = 0;
        for (let i = 0; i < selectionOptions.length; ++i) {
            if (selectionOptions[i].name === "") {
                selectionOptions[i].name = window.lang.convert('UNNAMED_ENTITY_LIFECYCLE') + '_' + unnamedCounter++;
            }
        }

        let dialogModel = new DialogControlModel(
            window.lang.convert('ASK_ADD_LIFECYCLETASK'),
            callback,
            window.lang.convert('OK_BUTTON'),
            window.lang.convert('CANCEL_BUTTON'),
            selectionOptions,
            {dialogType: 'documentDescription'}
        );
        let dialog = new DialogControl(dialogModel);

        dialog.showDialog();
    }
}