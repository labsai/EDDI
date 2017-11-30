function DialogUpdateManager(dataProvider, animated) {
    let synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    let newStateClassName = application.configuration.newStateClassName;
    let editedStateClassName = application.configuration.editedStateClassName;
    let idResolver = new HTMLIDResolver();


    let moveItemControlTree = function (event) {
        let item = event.item;
        let sender = event.sender;
        let receiver = event.receiver;
        let newIndex = event.newIndex;

        let children = sender.getModel().children;

        children.removeElement(item);
        receiver.getModel().children.splice(newIndex, 0, item);
    };

    let moveItemJSONTree = function (event, manager) {
        let item = event.item;
        let sender = event.sender;
        let receiver = event.receiver;

        let itemJSON = manager.getElementWithId(item.getModel().id);
        manager.deleteElementWithId(item.getModel().id);

        if (receiver == application.configuration.rootElementReceiver) {
            manager.addRootElementAtIndex(itemJSON, event.newIndex);
        } else {
            manager.addChildElementAtIdAndIndex(receiver.getModel().id, event.newIndex, itemJSON);
        }
    };

    this.sortableUpdatedItem = function (event) {
        let oldIndex = event.ui.item.data('old_index');
        let newIndex = event.ui.item.data('new_index');

        let moveItemIndexArray = function (array, oldIndexParam, newIndexParam) {
            let tmp = array.splice(oldIndexParam, 1)[0];
            array.splice(newIndexParam, 0, tmp);
        };

        let isRootLevel = false;
        let parentControl;
        try {
            parentControl = idResolver.resolveId($(event.sender).attr('id'));
        } catch (ex) {
            if (ex instanceof NoSuchIdException) {
                isRootLevel = true;
            } else {
                throw ex;
            }
        }

        let htmlId;
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
    };

    this.sortableReceivedItem = function (event) {
        let item = event.item;
        let sender = event.sender;
        let receiver = event.receiver;

        let tmpRepresentation = application.jsonRepresentationManager.clone();
        moveItemJSONTree(event, tmpRepresentation);

        let tmpSet = tmpRepresentation.getRuleSetView();

        let htmlId = '#' + item.getModel().idPrefix + item.getModel().id;

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
    };

    let updatePropertyJSON = function (event, boundValue, manager) {
        if (event.mappingPropertyJSON === "groupName") {
            manager.updateGroupName(event.oldValue, boundValue);
        } else {
            if (boundValue instanceof Array && event.mappingPropertyJSON !== 'actions') {
                for (let i = 0; i < boundValue.length; ++i) {
                    manager.updateElementWithId(event.sender.getModel().id, event.mappingPropertyJSON[i], boundValue[i]);
                }
            } else {
                manager.updateElementWithId(event.sender.getModel().id, event.mappingPropertyJSON, boundValue);
            }
        }
    };

    let updatePropertyControlTree = function (event, boundValue) {
        if (boundValue instanceof Array) {
            for (let i = 0; i < boundValue.length; ++i) {
                event.sender.getModel()[event.mappingPropertyControl[i]] = boundValue[i];
            }
        } else {
            event.sender.getModel()[event.mappingPropertyControl] = boundValue;
        }
    };

    this.valueChanged = function (event) {
        let boundValue;
        if (event.isUserInput) {
            boundValue = application.bindingManager.bindFromString(event.value);

            if (event.mappingPropertyControl === 'actions') {
                boundValue = boundValue.split(',');

                let tmp = [];
                for (let i = 0; i < boundValue.length; ++i) {
                    /** Trim values. */
                    boundValue[i] = jQuery.trim(boundValue[i]);

                    /** Remove empty strings. */
                    if (boundValue[i] !== '') {
                        tmp.push(boundValue[i]);
                    }
                }

                boundValue = tmp;
            }
        } else {
            boundValue = event.value;
        }

        let tmpRepresentation = application.jsonRepresentationManager.clone();

        updatePropertyJSON(event, boundValue, tmpRepresentation);

        let tmpSet = tmpRepresentation.getRuleSetView();

        let htmlId = '#' + event.sender.getModel().idPrefix + event.sender.getModel().id;

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