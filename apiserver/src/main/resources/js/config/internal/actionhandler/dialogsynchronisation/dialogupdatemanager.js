function DialogUpdateManager(dataProvider, animated) {
    var synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    var newStateClassName = application.configuration.newStateClassName;
    var editedStateClassName = application.configuration.editedStateClassName;
    var idResolver = new HTMLIDResolver();


    var moveItemControlTree = function (event) {
        var item = event.item;
        var sender = event.sender;
        var receiver = event.receiver;
        var newIndex = event.newIndex;

        var children = sender.getModel().children;

        children.removeElement(item);
        receiver.getModel().children.splice(newIndex, 0, item);
    }

    var moveItemJSONTree = function (event, manager) {
        var item = event.item;
        var sender = event.sender;
        var receiver = event.receiver;

        var itemJSON = manager.getElementWithId(item.getModel().id);
        manager.deleteElementWithId(item.getModel().id);

        if (receiver == application.configuration.rootElementReceiver) {
            manager.addRootElementAtIndex(itemJSON, event.newIndex);
        } else {
            manager.addChildElementAtIdAndIndex(receiver.getModel().id, event.newIndex, itemJSON);
        }
    }

    this.sortableUpdatedItem = function (event) {
        var oldIndex = event.ui.item.data('old_index');
        var newIndex = event.ui.item.data('new_index');

        var moveItemIndexArray = function (array, oldIndexParam, newIndexParam) {
            var tmp = array.splice(oldIndexParam, 1)[0];
            array.splice(newIndexParam, 0, tmp);
        };

        var isRootLevel = false;
        var parentControl;
        try {
            parentControl = idResolver.resolveId($(event.sender).attr('id'));
        } catch (ex) {
            if (ex instanceof NoSuchIdException) {
                isRootLevel = true;
            } else {
                throw ex;
            }
        }

        var htmlId;
        if (!isRootLevel) {
            moveItemIndexArray(parentControl.getModel().children, oldIndex, newIndex);

            htmlId = '#' + parentControl.getModel().children[newIndex].getModel().idPrefix +
                parentControl.getModel().children[newIndex].getModel().id;

            parentControl.getModel().children[newIndex].getModel().removeClass(application.configuration.newStateClassName);
            parentControl.getModel().children[newIndex].getModel().addClass(application.configuration.editedStateClassName);
        } else {
            htmlId = '#' + event.item.getModel().idPrefix +
                event.item.getModel().id;

            event.item.getModel().removeClass(application.configuration.newStateClassName);
            event.item.getModel().addClass(application.configuration.editedStateClassName);

            moveItemIndexArray(application.contentBuilder.getControls(), oldIndex, newIndex);
        }

        moveItemJSONTree(event, application.jsonRepresentationManager);

        $(htmlId).removeClass(application.configuration.newStateClassName);
        $(htmlId).addClass(application.configuration.editedStateClassName);

        application.reloadManager.changesHappened();
    }

    this.sortableReceivedItem = function (event) {
        var item = event.item;
        var sender = event.sender;
        var receiver = event.receiver;

        var tmpRepresentation = application.jsonRepresentationManager.clone();
        moveItemJSONTree(event, tmpRepresentation);

        var tmpSet = tmpRepresentation.getRuleSetView();

        var htmlId = '#' + item.getModel().idPrefix + item.getModel().id;

        if (animated) {
            $(htmlId).showLoadingIndicator();
        } else {
            $(htmlId).removeClass(newStateClassName);
            item.getModel().removeClass(newStateClassName);
            $(htmlId).addClass(editedStateClassName);
            item.getModel().addClass(editedStateClassName);
        }

        dataProvider.updateBehaviorRuleSet(tmpSet,
            function (httpCode, xmlHttpRequest, value) {
                if (application.httpCodeManager.successfulRequest(httpCode)) {
                    moveItemJSONTree(event, application.jsonRepresentationManager);
                    moveItemControlTree(event);

                    if (event.sortableLevel === application.configuration.packageInnerSortableLevel) {
                        if (sender.hasOwnProperty('adjustHeight')) {
                            sender.adjustHeight();
                        }

                        if (receiver.hasOwnProperty('adjustHeight')) {
                            receiverHeight = receiver.adjustHeight();
                        }

                        if (item.hasOwnProperty('observable')) {
                            /** Clean up action listeners. */
                            item.observable.removeObserver(sender.observer);
                            item.observable.addObserver(receiver.observer);
                        }

                        sender.observable.notify(new Event(sender, 'SizeChanged'));
                        receiver.observable.notify(new Event(receiver, 'SizeChanged'));
                    }

                    synchronisationHelper.updateActiveVersion(value);
                    application.reloadManager.changesHappened();
                    if (animated) {
                        $(htmlId).hideLoadingIndicator();
                    }
                } else {
                    synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                        if (success) {
                            /** Return to sending sortable if saving fails. */
                            $('#' + event.sortable.attr('id') + ' div:eq(' + event.oldIndex + ')').before($(htmlId));

                            $(event.sortable).sortable('refresh');

                            if (animated) {
                                $(htmlId).hideLoadingIndicator();
                            }
                        } else {
                            synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                        }
                    });
                }
            });
    }

    var updatePropertyJSON = function (event, boundValue, manager) {
        if (event.mappingPropertyJSON === "groupName") {
            manager.updateGroupName(event.oldValue, boundValue);
        } else {
            if (boundValue instanceof Array && event.mappingPropertyJSON != 'actions') {
                for (var i = 0; i < boundValue.length; ++i) {
                    manager.updateElementWithId(event.sender.getModel().id, event.mappingPropertyJSON[i], boundValue[i]);
                }
            } else {
                manager.updateElementWithId(event.sender.getModel().id, event.mappingPropertyJSON, boundValue);
            }
        }
    }

    var updatePropertyControlTree = function (event, boundValue) {
        if (boundValue instanceof Array) {
            for (var i = 0; i < boundValue.length; ++i) {
                event.sender.getModel()[event.mappingPropertyControl[i]] = boundValue[i];
            }
        } else {
            event.sender.getModel()[event.mappingPropertyControl] = boundValue;
        }
    }

    this.valueChanged = function (event) {
        var boundValue;
        if (event.isUserInput) {
            boundValue = application.bindingManager.bindFromString(event.value);

            if (event.mappingPropertyControl == 'actions') {
                boundValue = boundValue.split(',');

                var tmp = [];
                for (var i = 0; i < boundValue.length; ++i) {
                    /** Trim values. */
                    boundValue[i] = jQuery.trim(boundValue[i]);

                    /** Remove empty strings. */
                    if (boundValue[i] != '') {
                        tmp.push(boundValue[i]);
                    }
                }

                boundValue = tmp;
            }
        } else {
            boundValue = event.value;
        }

        var tmpRepresentation = application.jsonRepresentationManager.clone();

        updatePropertyJSON(event, boundValue, tmpRepresentation);

        var tmpSet = tmpRepresentation.getRuleSetView();

        var htmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

        if (animated) {
            $(htmlId).showLoadingIndicator();
        } else {
            $(htmlId).removeClass(newStateClassName);
            event.sender.getModel().removeClass(newStateClassName);
            $(htmlId).addClass(editedStateClassName);
            event.sender.getModel().addClass(editedStateClassName);
        }

        dataProvider.updateBehaviorRuleSet(tmpSet,
            function (httpCode, xmlHttpRequest, value) {
                if (application.httpCodeManager.successfulRequest(httpCode)) {
                    updatePropertyControlTree(event, boundValue);

                    updatePropertyJSON(event, boundValue, application.jsonRepresentationManager);

                    synchronisationHelper.updateActiveVersion(value);
                    application.reloadManager.changesHappened();

                    if (animated) {
                        $(htmlId).hideLoadingIndicator();
                    }
                } else {
                    synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                        if (success) {
                            /**
                             *  Return to old value if saving fails.
                             */
                            event.editable.text(event.oldValue);

                            if (animated) {
                                $(htmlId).hideLoadingIndicator();
                            }
                        } else {
                            synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                        }
                    });
                }
            });
    }
}