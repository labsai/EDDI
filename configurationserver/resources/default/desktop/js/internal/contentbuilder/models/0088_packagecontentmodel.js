function UnknownLifecycleTaskException(msg) {
    this.message = msg;
}

function PackageContentModel(dataProvider, actionHandler) {
    var firstLevelGroupControlIdPrefix = 'package_';
    var instance = this;

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        var models = [];

        var packageDescription = dataProvider.readActivePackage();
        console.log(packageDescription)

        var description = dataProvider.readActiveDocumentDescription();
        packageDescription.name = description.name;

        application.contentModelHelper.createResourceVersionSelectorControl();
        application.contentModelHelper.createDocumentDescriptorDisplayControl();
        application.contentModelHelper.createReturnToParentButton();
        application.contentModelHelper.createLanguageSelector();

        var packageModel = this.createPackageGroupControlModel(packageDescription);
        var packageControl = this.createGroupControl(packageModel, 'groupcontrol', sizeCallbackInstance, selectionCallbackInstance);

        for (var i = 0; i < packageDescription.packageExtensions.length; ++i) {
            var lifeCycleJSON = packageDescription.packageExtensions[i];

            var lifeCycle;
            try {
                lifeCycle = this.getLifeCycleTaskPlugin(lifeCycleJSON.type);
            } catch (ex) {
                if (ex instanceof UnknownLifecycleTaskException) {
                    /** Add unknown lifecycle tasks as generics. */
                    lifeCycle = {model:GenericLifecycleTaskModel, control:GenericLifecycleTaskControl};
                } else {
                    /** Propagate other exceptions. */
                    throw ex;
                }
            }

            var lifeCycleModel = new lifeCycle.model(lifeCycleJSON);
            var lifeCycleControl = new lifeCycle.control(lifeCycleModel);

            if (lifeCycleControl.hasOwnProperty('observable')) {
                lifeCycleControl.observable.addObserver(application.actionHandler.observer);
            }

            packageModel.addChild(lifeCycleControl);
        }

        models.push(packageControl);

        return models;
    }


    this.createPackageGroupControlModel = function (packageDescription) {
        var footerControls = [];

        var packageId = dataProvider.getNextIdGlobal();

        var footerModel = new FooterControlModel(packageId, firstLevelGroupControlIdPrefix + 'footer_', true);
        var footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        var headerControls = [];

        var retVal = new GroupControlModel(packageId, firstLevelGroupControlIdPrefix, packageDescription.name,
            footerControls, false, true, null, false, false);

        retVal.headerControls = headerControls;
        retVal.context = {namespace: 'ai.labs'};

        return retVal;
    }

    this.createGroupControl = function (model, CSSClassBase, sizeCallbackInstance, selectionCallbackInstance) {
        var gc = new GroupControl(model, CSSClassBase);

        gc.observable.addObserver(sizeCallbackInstance);
        //gc.observable.addObserver(selectionCallbackInstance);
        gc.observable.addObserver(actionHandler.observer);

        return gc;
    }

    this.getLifeCycleTaskPlugin = function (lifecycleTaskType) {
        if (application.pluginManager.plugins.lifecycletaskhandlers.hasOwnProperty(lifecycleTaskType)) {
            return application.pluginManager.plugins.lifecycletaskhandlers[lifecycleTaskType];
        } else {
            throw new UnknownLifecycleTaskException('No lifecycle task for type: ' + lifecycleTaskType);
        }
    }

    var addControl = function (parentControl, control) {
        var ownId = '#' + control.getModel().idPrefix + control.getModel().id;
        var parentId = '#' + application.configuration.referencePrefix + parentControl.getModel().idPrefix + parentControl.getModel().id;

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
                scrollTop:$(ownId).offset().top - $('#header').outerHeight() - $('#content').outerHeight() / 2 + $(ownId).outerHeight() / 2 + $('#content').scrollTop()
            }, 2000);
        }


        /** From: http://stackoverflow.com/questions/2834667/how-can-i-differentiate-a-manual-scroll-via-mousewheel-scrollbar-from-a-javasc */
        $('#content').bind("scroll mousedown DOMMouseScroll mousewheel keyup", function (e) {
            if (e.which > 0 || e.type === "mousedown" || e.type === "mousewheel") {
                $('#content').stop().unbind('scroll mousedown DOMMouseScroll mousewheel keyup'); // This identifies the scroll as a user action, stops the animation, then unbinds the event straight after (optional)
            }
        });
    }

    this.addChildControl = function (sender) {
        var callback = function (success, event) {
            if (success && event.documentDescription) {
                var value = application.url.getVersionAndIdForResource(event.documentDescription.resource);
                var id = value.id;
                var version = value.version;

                var extensionIdVersionIdentifier = "CACHED_EXTENSION_IDV_" + version + '_' + id;
                var extension = application.networkCacheManager.cachedNetworkCall(extensionIdVersionIdentifier,
                    application.dataProvider,
                    application.dataProvider.readExtension,
                    [id, version]
                );

                var lifeCycle;
                var lifeCycleModel;
                try {
                    lifeCycle = instance.getLifeCycleTaskPlugin(extension.type + '?version=' + version);
                    lifeCycleModel = new lifeCycle.model();
                } catch (ex) {
                    if (ex instanceof UnknownLifecycleTaskException) {
                        /**var errorModel = new DialogControlModel(window.lang.convert("PLUGIN_NOT_FOUND"), function() {},
                         window.lang.convert('OK_BUTTON'), false);
                         var errorControl = new DialogControl(errorModel);
                         errorControl.showDialog();
                         return;*/
                        lifeCycle = {model:GenericLifecycleTaskModel, control:GenericLifecycleTaskControl};
                        lifeCycleModel = new lifeCycle.model(application.jsonBuilderHelper.makeDefaultJSONFromExtension(extension.type));
                    }
                }

                var lifeCycleControl = new lifeCycle.control(lifeCycleModel);

                if (lifeCycleControl.hasOwnProperty('observable')) {
                    lifeCycleControl.observable.addObserver(application.actionHandler.observer);
                }

                addControl(sender, lifeCycleControl);
            }
        };

        var namespaceId = 'CACHED_NAMESPACE_' + sender.getModel().context.namespace;
        var selectionOptions = application.networkCacheManager.cachedNetworkCall(namespaceId, application.dataProvider,
            application.dataProvider.readExtensionDefinitions,
            [sender.getModel().context.namespace]);
        console.log('Options in namespace ' + sender.getModel().context.namespace + ' are: ');
        console.log(selectionOptions);

        var unnamedCounter = 0;
        for (var i = 0; i < selectionOptions.length; ++i) {
            if (selectionOptions[i].name == "") {
                selectionOptions[i].name = window.lang.convert('UNNAMED_ENTITY_LIFECYCLE') + '_' + unnamedCounter++;
            }
        }

        var dialogModel = new DialogControlModel(
            window.lang.convert('ASK_ADD_LIFECYCLETASK'),
            callback,
            window.lang.convert('OK_BUTTON'),
            window.lang.convert('CANCEL_BUTTON'),
            selectionOptions,
            {dialogType:'documentDescription'}
        );
        var dialog = new DialogControl(dialogModel);

        dialog.showDialog();
    }
}