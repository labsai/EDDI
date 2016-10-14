function DialogActionHandler(contentBuilder, dataProvider) {
    var instance = this;
    var dataProviderProxy = new DialogSaveProxy();
    var synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    var additionManager = new DialogAdditionManager(dataProviderProxy, false);
    var deletionManager = new DialogDeletionManager(dataProviderProxy, false);
    var updateManager = new DialogUpdateManager(dataProviderProxy, false);
    var versionHelper = new VersionHelper();
    var ignoreNextUpdate = false;

    function NoSuchBluePrintException(msg) {
        this.message = msg;
    }

    var deleteCallback = function (event) {
        instance.observer.eventReceived(event);
    }

    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'Save':
                if(application.reloadManager.hasChanges()) {
                    var tmpRepresentation = application.jsonRepresentationManager.clone();
                    var tmpSet = tmpRepresentation.getRuleSetView();

                    $('#content').showLoadingIndicator();

                    dataProvider.updateBehaviorRuleSet(tmpSet,
                        function (httpCode, xmlHttpRequest, value) {
                            if (application.httpCodeManager.successfulRequest(httpCode)) {
                                application.referenceUpdateManager.updateReferences(xmlHttpRequest.responseText,
                                    function(success) {
                                        application.reloadManager.performWithoutConfirmation(
                                            synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                        );
                                    });
                            } else {
                                synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                    if (success) {
                                        //in case server request fails, and user decides not to reload... just stay on the page.
                                        $('#content').hideLoadingIndicator();
                                    } else {
                                        application.reloadManager.performWithoutConfirmation(
                                            synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                        );
                                    }
                                });
                            }
                        }
                    );
                }
                break;
            case 'Cancel':
                if(application.reloadManager.hasChanges()) {
                    window.location.reload();
                }
                break;
            case 'ValueChanged':
                updateManager.valueChanged(event);
                break;
            case 'ControlAdded':
                additionManager.controlAdded(event);
                break;
            case 'ControlDeleted':
                deletionManager.controlDeleted(event);
                break;
            case 'AddColumn':
                /** This is the first event notification about the addition - the 'ControlAdded' event handles does the actual data access. */
                instance.handleAddChild(event, application.contentModelProvider);
                break;
            case 'CheckError':
                break;
            case 'RightButtonClicked':
                /** This is the first event notification about the addition - the 'ControlAdded' event handles does the actual data access. */
                instance.handleAddChild(event, application.contentModelProvider);
                break;
            case 'DeleteGroup':
                /** This is the first event notification about the deletion - the 'ControlDeleted' event handles does the actual data access. */
                instance.handleDelete(event, deleteCallback);
                break;
            case 'DeleteExtension':
                /** These are the first event notifications about the deletion - the 'ControlDeleted' event handles does the actual data access. */
                instance.handleDelete(event, deleteCallback);
                break;
        /** TODO: REST-Updates of client state. */
            case 'GroupSelected':
                break;
            case 'GroupLabelEdited':
                updateManager.valueChanged(event);
                break;
            case 'PackageLabelEdited':
                updateManager.valueChanged(event);
                break;
            case 'SortUpdateInner':
            case 'SortUpdateOuter':
            case 'SortUpdatePackageInner':
                if(ignoreNextUpdate) {
                    ignoreNextUpdate = false;
                    return;
                }

                updateManager.sortableUpdatedItem(event);
                break;
            case 'SortableReceivedItem':
                updateManager.sortableReceivedItem(event);

                /**
                 * Jquery sortable dispatches an update event right after the receive event,
                 * we don't want that to happen as the item is already placed at the right index on receive,
                 * thus we will ignore the next update event.
                 * */
                ignoreNextUpdate = true;
                break;
            case 'GotoVersion':
                var targetUri = event.sender.getModel().resourceUri;

                if(event.sender.getModel().anchors) {
                    targetUri += event.sender.getModel().anchors;
                }

                versionHelper.gotoResourceUri(targetUri);
                break;
        }
    });
}

DialogActionHandler.prototype = new GenericActionHandler();