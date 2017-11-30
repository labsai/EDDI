function DialogContentModel(dataProvider, pluginManager, contentBuilder, actionHandler) {
    let firstLevelGroupControlIdPrefix = 'behaviorgroup_';
    let firstLevelCSSClass = 'groupcontrol';
    let secondLevelCSSClass = 'packagecontrol';

    this.observable = new Observable();

    this.observable.addObserver(actionHandler.observer);

    function UnknownExtensionTypeException(msg) {
        this.message = msg;
    }

    /** Resolves models recursively by setting the children property to the child controls before constructing the root node. */
    this.recursiveResolveModel = function (extension) {
        let tmp = [];
        for (let i = 0; i < extension.children.length; ++i) {
            let res = this.recursiveResolveModel(extension.children[i]);

            if (typeof res !== "undefined") {
                tmp.push(res);
            }
        }

        let extensionModel;
        let extensionControl;
        try {
            let extensionPlugin = this.getBehaviorRuleExtensionPlugin(extension);

            if (extensionPlugin.model) {
                extensionModel = new extensionPlugin.model(extension);
                extensionModel.children = tmp;
            } else {
                throw 'Error: Misconfigured plugin: a model object must be defined. Extension: ' + extension.type + '.';
            }

            if (extensionPlugin.control) {
                extensionControl = new extensionPlugin.control(extensionModel);
            } else {
                throw 'Error: Misconfigured plugin: a control object must be defined. Extension: ' + extension.type + '.';
            }

        } catch (ex) {
            if (ex instanceof UnknownExtensionTypeException) {
                /** Print error's for unknown extension types. */
                console.log(ex.message);
                console.log(extension);

                extensionModel = new UnknownExtensionModel(extension);
                extensionControl = new UnknownExtensionControl(extensionModel);
            } else {
                /** Propagate other exception types. */
                throw ex;
            }
        }

        if (extensionControl.hasOwnProperty('observer')) {
            contentBuilder.observable.addObserver(extensionControl.observer);
        }

        if (extensionControl.hasOwnProperty('observable')) {
            extensionControl.observable.addObserver(actionHandler.observer);
        }

        return extensionControl;
    };

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        let models = [];

        let groups = dataProvider.getBehaviorGroups();

        application.contentModelHelper.createDocumentDescriptorDisplayControl();
        application.contentModelHelper.createResourceVersionSelectorControl();
        application.contentModelHelper.createReturnToParentButton();
        application.contentModelHelper.createLanguageSelector();

        application.jsonRepresentationManager.setBackingData(groups);

        for (let i = 0; i < groups.length; ++i) {
            let behaviorGroup = groups[i];
            let groupControlModel = this.createBehaviorGroupControlModel(behaviorGroup);

            let groupControl = this.createGroupControl(groupControlModel, firstLevelCSSClass, sizeCallbackInstance, selectionCallbackInstance);

            let rules = behaviorGroup.children;

            for (let j = 0; j < rules.length; ++j) {
                let rule = rules[j];
                let ruleControlModel = this.createBehaviorRuleControlModel(rule, groupControlModel);
                let ruleControl = this.createGroupControl(ruleControlModel, secondLevelCSSClass, sizeCallbackInstance, selectionCallbackInstance);

                for (let k = 0; k < rule.children.length; ++k) {
                    let extension = rule.children[k];

                    let extensionControl = this.recursiveResolveModel(extension);

                    /** Only add extensions that could be resolved. */
                    if (typeof extensionControl !== "undefined") {
                        ruleControlModel.addChild(extensionControl);
                    }
                }

                groupControlModel.addChild(ruleControl);
            }

            models.push(groupControl);
        }

        return models;
    };

    this.getBehaviorRuleExtensionPlugin = function (extension) {
        if (pluginManager.plugins.behaviorruleextensionhandlers.hasOwnProperty(extension.type)) {
            return pluginManager.plugins.behaviorruleextensionhandlers[extension.type];
        } else {
            throw new UnknownExtensionTypeException("Cannot display extension of type: " + extension.type + ".");
        }
    };

    this.createBehaviorGroupControlModel = function (behaviorGroup) {
        let footerControls = [];

        let footerModel = new FooterControlModel(behaviorGroup.id, firstLevelGroupControlIdPrefix + 'footer_', true);
        let footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        return new GroupControlModel(behaviorGroup.id, firstLevelGroupControlIdPrefix, behaviorGroup.name,
            footerControls, false, behaviorGroup.opened, null, behaviorGroup.editable, behaviorGroup.editable);
    };

    this.createBehaviorRuleControlModel = function (rule, parent) {
        let footerControls = [];

        let footerModel = new FooterControlModel(rule.id, 'parent_' + parent.id + '_rule_footer_', false);
        let footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        return new GroupControlModel(rule.id, 'parent_' + parent.id + '_rule_', rule.name,
            footerControls, rule.sequenceNumber, rule.opened, null, rule.editable, rule.editable, true, rule.actions);
    };

    this.createGroupControl = function (model, CSSClassBase, sizeCallbackInstance, selectionCallbackInstance) {
        let gc = new GroupControl(model, CSSClassBase);

        gc.observable.addObserver(sizeCallbackInstance);
        //gc.observable.addObserver(selectionCallbackInstance);
        gc.observable.addObserver(actionHandler.observer);

        return gc;
    };

    this.getDefaultBehaviorGroupControlData = function () {
        let retVal = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorGroup');
        retVal.id = dataProvider.getNextIdForBehaviorGroup();
        retVal.opened = true;

        return retVal;
    };

    this.getDefaultBehaviorRuleControlData = function () {
        let retVal = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRule');
        retVal.id = dataProvider.getNextIdForBehaviorRule();
        retVal.opened = true;

        return retVal;
    };

    let instance = this;

    let addControl = function (parentControl, control, isRootLevel) {
        let parentId;

        isFirstLevel = control.getModel().idPrefix.indexOf('parent_') === 0;

        if (isRootLevel) {
            parentId = '#toplevel';
        } else {
            parentId = '#' + application.configuration.referencePrefix + parentControl.getModel().idPrefix + parentControl.getModel().id;
        }

        let ownId = '#' + control.getModel().idPrefix + control.getModel().id;

        $(control.createRepresentation())
            .hide()
            .appendTo(parentId)
            .fadeIn();

        if (isRootLevel) {
            contentBuilder.addGroupControl(control);
        } else {
            parentControl.getModel().addChild(control);
            contentBuilder.readjustSize();
        }

        if (isRootLevel) {
            $('#content').stop().animate({
                scrollLeft: $(ownId).offset().left + $('#mainmenu').width() + $(ownId).outerWidth(),
                scrollTop: 0
            }, 2000);
        } else {
            if ($(ownId).offset().top - $('#header').outerHeight() > $('#content').outerHeight()) {
                $('#content').stop().animate({
                    scrollTop: $(ownId).offset().top - $('#header').outerHeight() - $('#content').outerHeight() / 2 + $(ownId).outerHeight() / 2 + $('#content').scrollTop()
                }, 2000);
            }
        }

        /** From: http://stackoverflow.com/questions/2834667/how-can-i-differentiate-a-manual-scroll-via-mousewheel-scrollbar-from-a-javasc */
        $('#content').bind("scroll mousedown DOMMouseScroll mousewheel keyup", function (e) {
            if (e.which > 0 || e.type === "mousedown" || e.type === "mousewheel") {
                $('#content').stop().unbind('scroll mousedown DOMMouseScroll mousewheel keyup'); // This identifies the scroll as a user action, stops the animation, then unbinds the event straight after (optional)
            }
        });

        control.registerButtonEvents();

        /** Notify the actionHandler. */
        let event = new Event(control, 'ControlAdded');
        event.parent = parentControl;

        if (isFirstLevel) {
            event.controlType = 'BehaviorRule';
        } else if (!isRootLevel) {
            event.controlType = 'BehaviorRuleExtension';
        } else {
            event.controlType = 'BehaviorGroup';
        }

        instance.observable.notify(event);
    };

    this.addChildControl = function (parentControl) {
        let control;
        let isFirstLevel, isSecondLevel, isRootLevel;

        if (parentControl.hasOwnProperty('getModel')) {
            isFirstLevel = parentControl.getModel().idPrefix.indexOf(firstLevelGroupControlIdPrefix) === 0;
            isSecondLevel = parentControl.getModel().idPrefix.indexOf('parent_') === 0;
            isRootLevel = false;
        } else {
            isRootLevel = true;
        }

        if (isRootLevel) {
            let data = this.getDefaultBehaviorGroupControlData();
            let model = this.createBehaviorGroupControlModel(data);

            control = this.createGroupControl(model, firstLevelCSSClass, application.contentBuilder.observer);
        } else if (isFirstLevel) {
            let data = this.getDefaultBehaviorRuleControlData();
            let model = this.createBehaviorRuleControlModel(data, parentControl);

            control = this.createGroupControl(model, secondLevelCSSClass, application.contentBuilder.observer);
        } else if (isSecondLevel) {
            let text = window.lang.convert('ASK_EXTENSION_TYPE');

            let formElements = [];
            let firstElementText = 'checked="checked"';
            for (let key in pluginManager.plugins.behaviorruleextensionhandlers) {
                formElements.push('<input class="plugintype_input" type="radio" name="plugintype" value="' + key + '"' + firstElementText + '/>' + key + '<br/>');
                firstElementText = '';
            }

            let callback = function (success, event) {
                if (success) {
                    let extensionPlugin = instance.getBehaviorRuleExtensionPlugin({type: event.command});

                    let extensionModel;
                    let extensionControl;
                    if (extensionPlugin.model) {
                        extensionModel = new extensionPlugin.model();
                        extensionModel.children = [];
                    } else {
                        throw 'Error: Misconfigured plugin: a model object must be defined. Extension: ' + extension.type + '.';
                    }

                    if (extensionPlugin.control) {
                        extensionControl = new extensionPlugin.control(extensionModel);
                    } else {
                        throw 'Error: Misconfigured plugin: a control object must be defined. Extension: ' + extension.type + '.';
                    }

                    if (extensionControl.hasOwnProperty('observer')) {
                        contentBuilder.observable.addObserver(extensionControl.observer);
                    }

                    if (extensionControl.hasOwnProperty('observable')) {
                        extensionControl.observable.addObserver(actionHandler.observer);
                        extensionControl.observable.addObserver(parentControl.observer);
                    }

                    addControl(parentControl, extensionControl, false);
                }
            };

            let model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'), formElements);

            let dialog = new DialogControl(model);

            dialog.showDialog();

            return;
        } else {
            return;
        }

        addControl(parentControl, control, isRootLevel);
    }
}