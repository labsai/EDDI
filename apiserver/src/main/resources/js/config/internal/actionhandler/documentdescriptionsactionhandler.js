function DocumentDescriptionsActionHandler(contentBuilder, dataProvider, page) {
    var instance = this;
    var synchronisationHelper = new DialogSynchronisationHelper(dataProvider);

    this.valueChanged = function (id, version, event) {
        var patchInstruction = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');
        patchInstruction.operation = 0; // = SET
        patchInstruction.document = event.newRowValue;

        event.editableHtmlControl.showLoadingIndicator();

        dataProvider.patchDocumentDescription(id, version, patchInstruction,
            function (httpCode, xmlHttpRequest, value) {
                if (application.httpCodeManager.successfulRequest(httpCode)) {
                    //synchronisationHelper.updateActiveVersion(value);
                    event.sender.getModel().data.rows[event.editedDataRowIndex][event.editedDataColumnIndex] = event.newEditableValue;
                    event.editableHtmlControl.hideLoadingIndicator();
                } else {
                    synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                        if (success) {
                            /**
                             *  Return to old value if saving fails.
                             */
                            event.editable.text(event.oldEditableValue);

                            event.editableHtmlControl.hideLoadingIndicator();
                        } else {
                            synchronisationHelper.handlePageReload(xmlHttpRequest.responseText);
                        }
                    });
                }
            }
        );
    }

    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'Add':
                if (typeof page !== 'undefined') {
                    switch (page) {
                        case 'bots':
                            dataProvider.createBot(application.jsonBlueprintFactory.makeBlueprintForObjectType('Bot'));
                            window.location.assign(window.location.href);
                            break;
                        case 'packages':
                            dataProvider.createPackage(application.jsonBlueprintFactory.makeBlueprintForObjectType('Package'));
                            window.location.assign(window.location.href);
                            break;
                        case 'outputs':
                            dataProvider.createOutputSet(application.jsonBlueprintFactory.makeBlueprintForObjectType('OutputConfigurationSet'));
                            window.location.assign(window.location.href);
                            break;
                        case 'dialogs':
                            dataProvider.createBehaviorRuleSet(application.jsonBlueprintFactory.makeBlueprintForObjectType('BehaviorRuleConfigurationSet'));
                            window.location.assign(window.location.href);
                            break;
                        case 'dictionaries':
                            dataProvider.createRegularDictionary(application.jsonBlueprintFactory.makeBlueprintForObjectType('RegularDictionaryConfiguration'));
                            window.location.assign(window.location.href);
                            break;
                        default:
//                throw new MalformedURLException('Navigation error: No such page: ' + page);
                            break;
                    }
                }
                break;
            case 'Delete':
                if (typeof page !== 'undefined') {
                    switch (page) {
                        case 'bots':
                            dataProvider.deleteBot(event.resourceId, event.resourceVersion);
                            window.location.assign(window.location.href);
                            break;
                        case 'packages':
                            dataProvider.deletePackage(event.resourceId, event.resourceVersion);
                            window.location.assign(window.location.href);
                            break;
                        case 'outputs':
                            dataProvider.deleteOutputSet(event.resourceId, event.resourceVersion);
                            window.location.assign(window.location.href);
                            break;
                        case 'dialogs':
                            dataProvider.deleteBehaviorRuleSet(event.resourceId, event.resourceVersion);
                            window.location.assign(window.location.href);
                            break;
                        case 'dictionaries':
                            dataProvider.deleteRegularDictionary(event.resourceId, event.resourceVersion);
                            window.location.assign(window.location.href);
                            break;
                        default:
//                throw new MalformedURLException('Navigation error: No such page: ' + page);
                            break;
                    }
                }
                break;
            case 'TableCellEdited':
                if (typeof event.resourceId !== 'undefined' && typeof event.resourceVersion !== 'undefined') {
                    instance.valueChanged(event.resourceId, event.resourceVersion, event);
                }
                break;
            case 'LimitChanged':
                if (event.oldValue != event.value) {
                    var query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    query.params.limit = event.value;
                    delete query.params.index;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'IndexChanged':
                if (event.oldValue != event.value) {
                    var query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }
                    query.params.index = event.value;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
            case 'SearchSelected':
                if (typeof event.value !== 'undefined' && event.value.length >= 0) {
                    var query = $.url.parse(window.location.href);

                    if (typeof query.params === 'undefined') {
                        query.params = {};
                    }

                    if (event.value.length > 0) {
                        query.params.filter = event.value;
                    } else {
                        delete query.params.filter;
                    }
                    delete query.params.index;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /*Reload the page with the new version active.*/
                    window.location.assign($.url.build(query));
                }
                break;
        }
    });
}