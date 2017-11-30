function PackageActionHandler(contentBuilder, dataProvider) {
    let synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    let editHandler = new PackageEditHelper();
    let idResolver = new HTMLIDResolver();
    let versionHelper = new VersionHelper();

    let deleteCallback = function (event) {
        instance.observer.eventReceived(event);
    };

    let instance = this;
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'Save':
                if (application.reloadManager.hasChanges()) {
                    let rootControls = application.contentBuilder.getControls();

                    let packageJSON = application.jsonBlueprintFactory.makeBlueprintForObjectType('Package');
                    let rootNode = rootControls[0];
                    for (let i = 0; i < rootNode.getModel().children.length; ++i) {
                        let child = rootNode.getModel().children[i];

                        if (child.getModel().hasOwnProperty('convertToJSON')) {
                            packageJSON.packageExtensions.push(child.getModel().convertToJSON());
                        }
                    }

                    $('#content').showLoadingIndicator();

                    dataProvider.updateActivePackage(packageJSON, function (httpCode, xmlHttpRequest, value) {
                        if (application.httpCodeManager.successfulRequest(httpCode)) {
                            application.referenceUpdateManager.updateReferences(xmlHttpRequest.responseText,
                                function (success) {
                                    application.reloadManager.performWithoutConfirmation(
                                        synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                    );
                                });
                        } else {
                            let callback = function (success) {
                                if (!success) {
                                    application.reloadManager.performWithoutConfirmation(
                                        synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                    );
                                }

                                $('#content').hideLoadingIndicator();
                            };

                            synchronisationHelper.showErrorDialogWithCallback(httpCode, callback);
                        }
                    });
                }
                break;
            case 'Cancel':
                if (application.reloadManager.hasChanges()) {
                    window.location.reload();
                }
                break;
            case 'RightButtonClicked':
                instance.handleAddChild(event, application.contentModelProvider);
                break;
            case 'DeleteElement':
                /** This is the first event notification about the deletion - the 'ControlDeleted' event handles does the actual data access. */
                instance.handleDelete(event, deleteCallback);
                break;
            case 'EditElement':
                // console.log(event.sender.getModel());
                editHandler.showEditDialogWithDefinition(event.sender.getModel(), event.configDefinition);
                break;
            case 'DeleteGroup':
                /** This is the first event notification about the deletion - the 'ControlDeleted' event handles does the actual data access. */
                instance.handleDelete(event, deleteCallback);
                break;
            case 'ControlDeleted':
                let model = event.sender.getModel();
                let modelId = '#' + model.idPrefix + model.id;

                let parentId = $(modelId).parent().attr('id');
                let parentControl = idResolver.resolveId(parentId);
                parentControl.getModel().children.removeElement(event.sender);

                synchronisationHelper.removeSenderFromDOM(event, true);
                application.reloadManager.changesHappened();
                break;
            case 'SortUpdateInner':
            case 'SortUpdatePackageInner':
                let oldIndex = event.ui.item.data('old_index');
                let newIndex = event.ui.item.data('new_index');

                let moveItemIndexArray = function (array, oldIndexParam, newIndexParam) {
                    let tmp = array.splice(oldIndexParam, 1)[0];
                    array.splice(newIndexParam, 0, tmp);
                };

                let groupControl = idResolver.resolveId($(event.sender).attr('id'));

                moveItemIndexArray(groupControl.getModel().children, oldIndex, newIndex);

                let htmlId = '#' + groupControl.getModel().children[newIndex].getModel().backingGroupControl.getModel().idPrefix +
                    groupControl.getModel().children[newIndex].getModel().backingGroupControl.getModel().id;

                $(htmlId).removeClass(application.configuration.newStateClassName);
                groupControl.getModel().children[newIndex].getModel().backingGroupControl.getModel().removeClass(application.configuration.newStateClassName);
                $(htmlId).addClass(application.configuration.editedStateClassName);
                groupControl.getModel().children[newIndex].getModel().backingGroupControl.getModel().addClass(application.configuration.editedStateClassName);

                application.reloadManager.changesHappened();
                break;
            case 'GotoVersion':
                let targetUri = event.sender.getModel().resourceUri;

                if (event.sender.getModel().anchors) {
                    targetUri += event.sender.getModel().anchors;
                }

                versionHelper.gotoResourceUri(targetUri);
                break;
            default:
                break;
        }
    });
}

PackageActionHandler.prototype = new GenericActionHandler();