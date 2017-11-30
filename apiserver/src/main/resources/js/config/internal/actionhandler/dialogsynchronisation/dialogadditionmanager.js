function DialogAdditionManager(dataProvider, animated) {
    let synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    let newStateClassName = application.configuration.newStateClassName;

    this.controlAdded = function (event) {
        let parentId;
        switch (event.controlType) {
            case 'BehaviorGroup':
                let newGroup = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorGroup');

                newGroup.id = event.sender.getModel().id;
                newGroup.selected = event.sender.getModel().selected;
                newGroup.opened = event.sender.getModel().opened;

                let groupId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                if (!animated) {
                    $(groupId).addClass(newStateClassName);
                    event.sender.getModel().addClass(newStateClassName);
                }

                application.jsonRepresentationManager.addRootElement(newGroup);
                application.reloadManager.changesHappened();
                break;
            case 'BehaviorRule':
                let newRule = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRule');

                newRule.id = event.sender.getModel().id;
                newRule.selected = event.sender.getModel().selected;
                newRule.opened = event.sender.getModel().opened;

                let tmpRepresentation = application.jsonRepresentationManager.clone();

                parentId = event.parent.getModel().id;
                tmpRepresentation.addChildElementAtId(parentId, newRule);
                let tmpSet = tmpRepresentation.getRuleSetView();

                let htmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                if (animated) {
                    $(htmlId).showLoadingIndicator();
                } else {
                    $(htmlId).addClass(newStateClassName);
                    event.sender.getModel().addClass(newStateClassName);
                }

                dataProvider.updateBehaviorRuleSet(tmpSet,
                    function (httpCode, xmlHttpRequest, value) {
                        if (application.httpCodeManager.successfulRequest(httpCode)) {
                            application.jsonRepresentationManager.addChildElementAtId(parentId, newRule);

                            synchronisationHelper.updateActiveVersion(value);
                            application.reloadManager.changesHappened();

                            if (animated) {
                                $(htmlId).hideLoadingIndicator();
                            }
                        } else {
                            synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                if (success) {
                                    synchronisationHelper.removeSenderFromControlTree(event);
                                    $(htmlId).hideLoadingIndicator();
                                    synchronisationHelper.removeSenderFromDOM(event, false);
                                } else {
                                    synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                                }
                            });
                        }
                    });
                break;
            case 'BehaviorRuleExtension':
                parentId = event.parent.getModel().id;

                let plugins = application.pluginManager.plugins.behaviorruleextensionhandlers;
                let newExtensionJSON = null;
                for (let key in plugins) {
                    if (event.sender instanceof plugins[key].control) {
                        newExtensionJSON = application.jsonBlueprintFactory.makeBlueprintForObjectType(key);
                        break;
                    }
                }

                if (newExtensionJSON !== null) {
                    newExtensionJSON.id = event.sender.getModel().id;

                    let idResolver = new HTMLIDResolver();

                    let parentRuleId = event.parent.getModel().id;
                    let parentControl = event.parent;

                    /** Get the first parent that is a GroupControl (which is the representation of the behaviorRule) */
                    while (!(parentControl instanceof GroupControl)) {
                        let parentModel = parentControl.getModel();
                        parentRuleId = $('#' + parentModel.idPrefix + parentModel.id).parent().attr('id');
                        parentControl = idResolver.resolveId(parentRuleId);
                    }

                    let tmpRepresentation = application.jsonRepresentationManager.clone();

                    tmpRepresentation.addChildElementAtId(parentId, newExtensionJSON);

                    let tmpSet = tmpRepresentation.getRuleSetView();

                    let parentHtmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                    if (animated) {
                        $(parentHtmlId).showLoadingIndicator();
                    } else {
                        $(parentHtmlId).addClass(newStateClassName);
                        event.sender.getModel().addClass(newStateClassName);
                    }

                    dataProvider.updateBehaviorRuleSet(tmpSet,
                        function (httpCode, xmlHttpRequest, value) {
                            if (application.httpCodeManager.successfulRequest(httpCode)) {
                                application.jsonRepresentationManager.addChildElementAtId(parentId, newExtensionJSON);

                                synchronisationHelper.updateActiveVersion(value);
                                application.reloadManager.changesHappened();

                                if (animated) {
                                    $(parentHtmlId).hideLoadingIndicator();
                                }
                            } else {
                                synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                    if (success) {
                                        synchronisationHelper.removeSenderFromControlTree(event);
                                        $(parentHtmlId).hideLoadingIndicator();
                                        synchronisationHelper.removeSenderFromDOM(event, false);
                                    } else {
                                        synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                                    }
                                });
                            }
                        });
                } else {
                    throw new NoSuchBluePrintException('Could not find a blueprint for object: ' + JSON.stringify(event.sender));
                }
                break;
        }
    }
}