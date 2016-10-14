function DialogAdditionManager(dataProvider, animated) {
    var synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    var newStateClassName = application.configuration.newStateClassName;

    this.controlAdded = function (event) {
        switch (event.controlType) {
            case 'BehaviorGroup':
                var newGroup = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorGroup');

                newGroup.id = event.sender.getModel().id;
                newGroup.selected = event.sender.getModel().selected;
                newGroup.opened = event.sender.getModel().opened;

                var groupId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                if(!animated) {
                    $(groupId).addClass(newStateClassName);
                    event.sender.getModel().addClass(newStateClassName);
                }

                application.jsonRepresentationManager.addRootElement(newGroup);
                application.reloadManager.changesHappened();
                break;
            case 'BehaviorRule':
                var newRule = application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRule');

                newRule.id = event.sender.getModel().id;
                newRule.selected = event.sender.getModel().selected;
                newRule.opened = event.sender.getModel().opened;

                var tmpRepresentation = application.jsonRepresentationManager.clone();

                var parentId = event.parent.getModel().id;
                tmpRepresentation.addChildElementAtId(parentId, newRule);
                var tmpSet = tmpRepresentation.getRuleSetView();

                var htmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                if(animated) {
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

                            if(animated) {
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
                var parentId = event.parent.getModel().id;

                var plugins = application.pluginManager.plugins.behaviorruleextensionhandlers;
                var newExtensionJSON = null;
                for (var key in plugins) {
                    if (event.sender instanceof plugins[key].control) {
                        newExtensionJSON = application.jsonBlueprintFactory.makeBlueprintForObjectType(key);
                        break;
                    }
                }

                if (newExtensionJSON != null) {
                    newExtensionJSON.id = event.sender.getModel().id;

                    var idResolver = new HTMLIDResolver();

                    var parentRuleId = event.parent.getModel().id;
                    var parentControl = event.parent;

                    /** Get the first parent that is a GroupControl (which is the representation of the behaviorRule) */
                    while (!(parentControl instanceof GroupControl)) {
                        var parentModel = parentControl.getModel();
                        parentRuleId = $('#' + parentModel.idPrefix + parentModel.id).parent().attr('id');
                        parentControl = idResolver.resolveId(parentRuleId);
                    }

                    var tmpRepresentation = application.jsonRepresentationManager.clone();

                    tmpRepresentation.addChildElementAtId(parentId, newExtensionJSON);

                    var tmpSet = tmpRepresentation.getRuleSetView();

                    var parentHtmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                    if(animated) {
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

                                if(animated) {
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