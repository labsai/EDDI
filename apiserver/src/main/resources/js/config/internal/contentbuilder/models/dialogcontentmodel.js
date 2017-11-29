function DialogContentModel(dataProvider, pluginManager, contentBuilder, actionHandler) {
    var firstLevelGroupControlIdPrefix = 'behaviorgroup_';
    var firstLevelCSSClass = 'groupcontrol';
    var secondLevelCSSClass = 'packagecontrol';

    this.observable = new Observable();

    this.observable.addObserver(actionHandler.observer);

    function UnknownExtensionTypeException(msg) {
        this.message = msg;
    }

    /** Resolves models recursively by setting the children property to the child controls before constructing the root node. */
    this.recursiveResolveModel = function (extension) {
        var tmp = new Array();
        for (var i = 0; i < extension.children.length; ++i) {
            var res = this.recursiveResolveModel(extension.children[i]);

            if (typeof res !== "undefined") {
                tmp.push(res);
            }
        }

        var extensionModel;
        var extensionControl;
        try {
            var extensionPlugin = this.getBehaviorRuleExtensionPlugin(extension);

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
    }

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        var models = [];

        var groups = dataProvider.getBehaviorGroups();

        application.contentModelHelper.createDocumentDescriptorDisplayControl();
        application.contentModelHelper.createResourceVersionSelectorControl();
        application.contentModelHelper.createReturnToParentButton();
        application.contentModelHelper.createLanguageSelector();

        application.jsonRepresentationManager.setBackingData(groups);

        for (var i = 0; i < groups.length; ++i) {
            var behaviorGroup = groups[i];
            var groupControlModel = this.createBehaviorGroupControlModel(behaviorGroup);

            var groupControl = this.createGroupControl(groupControlModel, firstLevelCSSClass, sizeCallbackInstance, selectionCallbackInstance);

            var rules = behaviorGroup.children;

            for (var j = 0; j < rules.length; ++j) {
                var rule = rules[j];
                var ruleControlModel = this.createBehaviorRuleControlModel(rule, groupControlModel);
                var ruleControl = this.createGroupControl(ruleControlModel, secondLevelCSSClass, sizeCallbackInstance, selectionCallbackInstance);

                for (var k = 0; k < rule.children.length; ++k) {
                    var extension = rule.children[k];

                    var extensionControl = this.recursiveResolveModel(extension);

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
    }

    this.getBehaviorRuleExtensionPlugin = function (extension) {
        if (pluginManager.plugins.behaviorruleextensionhandlers.hasOwnProperty(extension.type)) {
            return pluginManager.plugins.behaviorruleextensionhandlers[extension.type];
        } else {
            throw new UnknownExtensionTypeException("Cannot display extension of type: " + extension.type + ".");
        }
    }

    this.createBehaviorGroupControlModel = function (behaviorGroup) {
        var footerControls = [];

        var footerModel = new FooterControlModel(behaviorGroup.id, firstLevelGroupControlIdPrefix + 'footer_', true);
        var footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        return new GroupControlModel(behaviorGroup.id, firstLevelGroupControlIdPrefix, behaviorGroup.name,
            footerControls, false, behaviorGroup.opened, null, behaviorGroup.editable, behaviorGroup.editable);
    }

    this.createBehaviorRuleControlModel = function (rule, parent) {
        var footerControls = [];

        var footerModel = new FooterControlModel(rule.id, 'parent_' + parent.id + '_rule_footer_', false);
        var footerControl = new FooterControl(footerModel, 'footercontrol');

        footerControls.push(footerControl);

        return new GroupControlModel(rule.id, 'parent_' + parent.id + '_rule_', rule.name,
            footerControls, rule.sequenceNumber, rule.opened, null, rule.editable, rule.editable, true, rule.actions);
    }

    this.createGroupControl = function (model, CSSClassBase, sizeCallbackInstance, selectionCallbackInstance) {
        var gc = new GroupControl(model, CSSClassBase);

        gc.observable.addObserver(sizeCallbackInstance);
        //gc.observable.addObserver(selectionCallbackInstance);
        gc.observable.addObserver(actionHandler.observer);

        return gc;
    }

    this.getDefaultBehaviorGroupControlData = function () {
        var retVal = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorGroup');
        retVal.id = dataProvider.getNextIdForBehaviorGroup();
        retVal.opened = true;

        return retVal;
    }

    this.getDefaultBehaviorRuleControlData = function () {
        var retVal = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRule');
        retVal.id = dataProvider.getNextIdForBehaviorRule();
        retVal.opened = true;

        return retVal;
    }

    var instance = this;

    var addControl = function (parentControl, control, isRootLevel) {
        var parentId;

        isFirstLevel = control.getModel().idPrefix.indexOf('parent_') == 0;

        if (isRootLevel) {
            parentId = '#toplevel';
        } else {
            parentId = '#' + application.configuration.referencePrefix + parentControl.getModel().idPrefix + parentControl.getModel().id;
        }

        var ownId = '#' + control.getModel().idPrefix + control.getModel().id;

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
        var event = new Event(control, 'ControlAdded');
        event.parent = parentControl;

        if (isFirstLevel) {
            event.controlType = 'BehaviorRule';
        } else if (!isRootLevel) {
            event.controlType = 'BehaviorRuleExtension';
        } else {
            event.controlType = 'BehaviorGroup';
        }

        instance.observable.notify(event);
    }

    this.addChildControl = function (parentControl) {
        var control;
        var isFirstLevel, isSecondLevel, isRootLevel;

        if (parentControl.hasOwnProperty('getModel')) {
            isFirstLevel = parentControl.getModel().idPrefix.indexOf(firstLevelGroupControlIdPrefix) == 0;
            isSecondLevel = parentControl.getModel().idPrefix.indexOf('parent_') == 0;
            isRootLevel = false;
        } else {
            isRootLevel = true;
        }

        if (isRootLevel) {
            var data = this.getDefaultBehaviorGroupControlData();
            var model = this.createBehaviorGroupControlModel(data);

            control = this.createGroupControl(model, firstLevelCSSClass, application.contentBuilder.observer);
        } else if (isFirstLevel) {
            var data = this.getDefaultBehaviorRuleControlData();
            var model = this.createBehaviorRuleControlModel(data, parentControl);

            control = this.createGroupControl(model, secondLevelCSSClass, application.contentBuilder.observer);
        } else if (isSecondLevel) {
            var text = window.lang.convert('ASK_EXTENSION_TYPE');

            var formElements = [];
            var firstElementText = 'checked="checked"';
            for (var key in pluginManager.plugins.behaviorruleextensionhandlers) {
                formElements.push('<input class="plugintype_input" type="radio" name="plugintype" value="' + key + '"' + firstElementText + '/>' + key + '<br/>');
                firstElementText = '';
            }

            var callback = function (success, event) {
                if (success) {
                    var extensionPlugin = instance.getBehaviorRuleExtensionPlugin({type: event.command});

                    var extensionModel;
                    var extensionControl;
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

            var model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'), formElements);

            var dialog = new DialogControl(model);

            dialog.showDialog();

            return;
        } else {
            return;
        }

        addControl(parentControl, control, isRootLevel);
    }
}