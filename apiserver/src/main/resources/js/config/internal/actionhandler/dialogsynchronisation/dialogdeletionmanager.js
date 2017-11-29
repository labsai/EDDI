function DialogDeletionManager(dataProvider, animated) {
    var synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    var firstLevelGroupControlIdPrefix = 'behaviorgroup_';

    this.controlDeleted = function (event) {
        switch (event.firstCommand) {
            case 'DeleteGroup':
                /** This is the first event notification about the deletion - the 'ControlDeleted' event handles does the actual data access. */
                var isFirstLevel = event.sender.getModel().idPrefix.indexOf(firstLevelGroupControlIdPrefix) == 0;
                var isSecondLevel = event.sender.getModel().idPrefix.indexOf('parent_') == 0;

                if (isFirstLevel) {
                    var htmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                    if (animated) {
                        $(htmlId).showLoadingIndicator();
                    }

                    var tmpRepresentation = application.jsonRepresentationManager.clone();

                    tmpRepresentation.deleteElementWithId(event.sender.getModel().id);

                    var tmpSet = tmpRepresentation.getRuleSetView();

                    dataProvider.updateBehaviorRuleSet(tmpSet,
                        function (httpCode, xmlHttpRequest, value) {
                            if (application.httpCodeManager.successfulRequest(httpCode)) {
                                /** Remove the group */
                                application.jsonRepresentationManager.deleteElementWithId(event.sender.getModel().id);
                                application.contentBuilder.getControls().removeElement(event.sender);

                                synchronisationHelper.updateActiveVersion(value);
                                application.reloadManager.changesHappened();

                                if (animated) {
                                    $(htmlId).hideLoadingIndicator();
                                }

                                synchronisationHelper.removeSenderFromDOM(event);
                            } else {
                                synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                    if (success) {
                                        if (animated) {
                                            $(htmlId).hideLoadingIndicator();
                                        }
                                    } else {
                                        synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                                    }
                                });
                            }
                        });
                } else if (isSecondLevel) {
                    var groupId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                    if (animated) {
                        $(groupId).showLoadingIndicator();
                    }

                    var tmpRepresentation = application.jsonRepresentationManager.clone();

                    tmpRepresentation.deleteElementWithId(event.sender.getModel().id);

                    var tmpSet = tmpRepresentation.getRuleSetView();

                    dataProvider.updateBehaviorRuleSet(tmpSet,
                        function (httpCode, xmlHttpRequest, value) {
                            if (application.httpCodeManager.successfulRequest(httpCode)) {
                                application.jsonRepresentationManager.deleteElementWithId(event.sender.getModel().id);

                                var idResolver = new HTMLIDResolver();

                                var parentId = $('#' + event.sender.getModel().idPrefix + event.sender.getModel().id).parent().attr('id');
                                var parentControl = idResolver.resolveId(parentId);

                                parentControl.getModel().children.removeElement(event.sender);

                                synchronisationHelper.updateActiveVersion(value);
                                application.reloadManager.changesHappened();

                                if (animated) {
                                    $(groupId).hideLoadingIndicator();
                                }

                                synchronisationHelper.removeSenderFromDOM(event);
                            } else {
                                synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                    if (success) {
                                        if (animated) {
                                            $(groupId).hideLoadingIndicator();
                                        }
                                    } else {
                                        synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                                    }
                                });
                            }
                        });
                }
                break;
            case 'DeleteExtension':
                var model = event.sender.getModel();

                var idResolver = new HTMLIDResolver();

                var parentId = $('#' + model.idPrefix + model.id).parent().attr('id');

                var parentControl = idResolver.resolveId(parentId);

                /** Get the first parent that is a GroupControl (which is the representation of the behaviorRule) */
                while (!(parentControl instanceof GroupControl)) {
                    var parentModel = parentControl.getModel();
                    parentId = $('#' + parentModel.idPrefix + parentModel.id).parent().attr('id');
                    parentControl = idResolver.resolveId(parentId);
                }

                var tmpRepresentation = application.jsonRepresentationManager.clone();

                tmpRepresentation.deleteElementWithId(model.id);

                var tmpSet = tmpRepresentation.getRuleSetView();

                var htmlId = '#' + model.idPrefix + model.id;

                if (animated) {
                    $(htmlId).showLoadingIndicator();
                }

                dataProvider.updateBehaviorRuleSet(tmpSet,
                    function (httpCode, xmlHttpRequest, value) {
                        if (application.httpCodeManager.successfulRequest(httpCode)) {
                            application.jsonRepresentationManager.deleteElementWithId(model.id);

                            parentId = $(htmlId).parent().attr('id');
                            parentControl = idResolver.resolveId(parentId);
                            parentControl.getModel().children.removeElement(event.sender);

                            synchronisationHelper.updateActiveVersion(value);
                            application.reloadManager.changesHappened();

                            if (animated) {
                                $(htmlId).hideLoadingIndicator();
                            }

                            synchronisationHelper.removeSenderFromDOM(event);
                        } else {
                            synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                if (success) {
                                    if (animated) {
                                        $(htmlId).hideLoadingIndicator();
                                    }
                                } else {
                                    synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                                }
                            });
                        }
                    });
                break;
        }
    }
}