function TestCaseDescriptionsActionHandler(contentBuilder, dataProvider) {
    let instance = this;
    let synchronisationHelper = new DialogSynchronisationHelper(dataProvider);

    this.valueChanged = function (id, version, event) {
        let patchInstruction = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');
        patchInstruction.operation = 0; // = SET
        patchInstruction.document = event.newRowValue;

        event.editableHtmlControl.showLoadingIndicator();

        dataProvider.patchTestCaseDescription(id, version, patchInstruction,
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
    };

    let runTestCaseFunction = function (event, providerInstance, runTestCase, getRunTestCaseStatus) {
        let intervalNumber;

        let completion = function (httpCode, xmlHttpRequest, value) {
            if (httpCode === 202) {
                let timeoutFunc = function () {
                    let returnValue = getRunTestCaseStatus.apply(providerInstance, [event.resourceId, event.resourceVersion]);

                    application.contentModelProvider.setTestCaseResultState(event.rowId, returnValue);

                    if (returnValue !== 'IN_PROGRESS') {
                        clearInterval(intervalNumber);
                    }
                };

                intervalNumber = setInterval(timeoutFunc, 1000);
            }
            else {
                application.contentModelProvider.setTestCaseResultState(event.rowId, 'ERROR');
            }
        };

        application.contentModelProvider.setTestCaseResultState(event.rowId, 'IN_PROGRESS');

        runTestCase.apply(providerInstance, [event.resourceId, event.resourceVersion, completion]);
    };

    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'TableCellEdited':
                if (typeof event.resourceId !== 'undefined' && typeof event.resourceVersion !== 'undefined') {
                    instance.valueChanged(event.resourceId, event.resourceVersion, event);
                }
                break;
            case 'Delete':
                dataProvider.deleteTestCase(event.resourceId, event.resourceVersion);
                window.location.assign(window.location.href);
                break;
            case 'Run':
                runTestCaseFunction(event, dataProvider, dataProvider.runTestCase, dataProvider.getRunTestCaseStatus);
                break;
            case 'RunAll':
                let model = application.contentModelProvider.getTableControl().getModel();
                let tableRowPrefix = application.contentModelProvider.getTableControl().getTableRowPrefix();
                $('#' + model.idPrefix + model.id + ' > tbody > tr').each(function () {
                    let rowId = $(this).attr("id");
                    let selectedDataRowIndex;

                    if (typeof rowId !== 'undefined' && rowId.indexOf(tableRowPrefix) === 0) {
                        selectedDataRowIndex = rowId.substring(tableRowPrefix.length, rowId.length);
                    }

                    let runEvent = new Event(instance, 'Run');
                    if (typeof model.data.resourceParams !== 'undefined' && typeof selectedDataRowIndex !== 'undefined') {
                        runEvent.resourceId = model.data.resourceParams[selectedDataRowIndex].id;
                        runEvent.resourceVersion = model.data.resourceParams[selectedDataRowIndex].version;
                        runEvent.rowId = rowId;
                        runTestCaseFunction(runEvent, dataProvider, dataProvider.runTestCase, dataProvider.getRunTestCaseStatus)
                    }
                });
                break;
            case 'LimitChanged':
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

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
                if (event.oldValue !== event.value) {
                    let query = $.url.parse(window.location.href);

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
                    let query = $.url.parse(window.location.href);

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
            default:
                break;
        }
    });
}