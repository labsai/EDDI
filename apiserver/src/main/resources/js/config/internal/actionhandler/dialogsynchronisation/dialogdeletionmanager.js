function DialogDeletionManager(dataProvider, animated) {
    let synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    let firstLevelGroupControlIdPrefix = 'behaviorgroup_';

    this.controlDeleted = function (event) {
        switch (event.firstCommand) {
            case 'DeleteGroup':
                /** This is the first event notification about the deletion - the 'ControlDeleted' event handles does the actual data access. */
                let isFirstLevel = event.sender.getModel().idPrefix.indexOf(firstLevelGroupControlIdPrefix) === 0;
                let isSecondLevel = event.sender.getModel().idPrefix.indexOf('parent_') === 0;

                if (isFirstLevel) {
                    let htmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                    if (animated) {
                        $(htmlId).showLoadingIndicator();
                    }

                    let tmpRepresentation = application.jsonRepresentationManager.clone();

                    tmpRepresentation.deleteElementWithId(event.sender.getModel().id);

                    let tmpSet = tmpRepresentation.getRuleSetView();

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
                    let groupId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

                    if (animated) {
                        $(groupId).showLoadingIndicator();
                    }

                    let tmpRepresentation = application.jsonRepresentationManager.clone();

                    tmpRepresentation.deleteElementWithId(event.sender.getModel().id);

                    let tmpSet = tmpRepresentation.getRuleSetView();

                    dataProvider.updateBehaviorRuleSet(tmpSet,
                        function (httpCode, xmlHttpRequest, value) {
                            if (application.httpCodeManager.successfulRequest(httpCode)) {
                                application.jsonRepresentationManager.deleteElementWithId(event.sender.getModel().id);

                                let idResolver = new HTMLIDResolver();

                                let parentId = $('#' + event.sender.getModel().idPrefix + event.sender.getModel().id).parent().attr('id');
                                let parentControl = idResolver.resolveId(parentId);

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
                let model = event.sender.getModel();

                let idResolver = new HTMLIDResolver();

                let parentId = $('#' + model.idPrefix + model.id).parent().attr('id');

                let parentControl = idResolver.resolveId(parentId);

                /** Get the first parent that is a GroupControl (which is the representation of the behaviorRule) */
                while (!(parentControl instanceof GroupControl)) {
                    let parentModel = parentControl.getModel();
                    parentId = $('#' + parentModel.idPrefix + parentModel.id).parent().attr('id');
                    parentControl = idResolver.resolveId(parentId);
                }

                let tmpRepresentation = application.jsonRepresentationManager.clone();

                tmpRepresentation.deleteElementWithId(model.id);

                let tmpSet = tmpRepresentation.getRuleSetView();

                let htmlId = '#' + model.idPrefix + model.id;

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