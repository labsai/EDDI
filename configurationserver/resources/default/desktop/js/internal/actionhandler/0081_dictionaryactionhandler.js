function DictionaryActionHandler(contentBuilder, dataProvider) {
    var instance = this;
    var synchronisationHelper = new DialogSynchronisationHelper(dataProvider);
    var versionHelper = new VersionHelper();
    var instructionCache = new PatchInstructionCache(dataProvider);

    this.deleteRows = function (tableControl) {
        if (tableControl.getSelectedRows().length > 0) {
            var patchInstructions = [];

            tableControl.getSelectedRows().each(function () {
                var rowId = $(this).attr("id");
                var rowIndex;

                if (typeof rowId !== 'undefined' && rowId.indexOf(tableControl.getTableRowPrefix()) == 0) {
                    rowIndex = rowId.substring(tableControl.getTableRowPrefix().length, rowId.length);
                }

                if (typeof rowIndex !== 'undefined') {
                    var patchInstruction = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');
                    patchInstruction.operation = 1; // = DELETE
                    patchInstruction.document = application.jsonBlueprintFactory.makeBlueprintForObjectType('RegularDictionaryConfiguration');
                    patchInstruction.document.language = dataProvider.dataProviderState.getRegularDictionaryConfigurationLanguage();
                    var data = application.jsonBlueprintFactory.makeBlueprintForObjectType(tableControl.getModel().data.context.dataType);
                    for (var i = 0; i < tableControl.getModel().cols.length; i++) {
                        if (tableControl.getModel().cols[i].isServerData) {
                            data[tableControl.getModel().cols[i].context.columnIdentifier] = tableControl.getModel().data.rows[rowIndex][i];
                        }
                    }
                    patchInstruction.document[tableControl.getModel().data.context.dataType].push(data);

                    patchInstructions.push(patchInstruction);
                }
            });

            instructionCache.patchActiveRegularDictionary(patchInstructions,
                function (httpCode, xmlHttpRequest, value) {
                    if (application.httpCodeManager.successfulRequest(httpCode)) {
                        synchronisationHelper.updateActiveVersion(value);

                        tableControl.getSelectedRows().each(function () {
                            $(this).hide();
                        });

                        application.reloadManager.changesHappened();
                    } else {
                        synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                if (success) {
                                    ;
                                } else {
                                    application.reloadManager.performWithoutConfirmation(
                                        synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                    );
                                }
                            }
                        );
                    }
                }
            );
        }
    }

    this.addRow = function (event) {
        var patchInstruction = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');
        patchInstruction.operation = 0; // = SET
        patchInstruction.document = application.jsonBlueprintFactory.makeBlueprintForObjectType('RegularDictionaryConfiguration');
        patchInstruction.document.language = dataProvider.dataProviderState.getRegularDictionaryConfigurationLanguage();
        patchInstruction.document[event.dataType].push(event.newRowValue);

        instructionCache.patchActiveRegularDictionary([patchInstruction],
            function (httpCode, xmlHttpRequest, value) {
                if (application.httpCodeManager.successfulRequest(httpCode)) {
                    synchronisationHelper.updateActiveVersion(value);

                    if(event.dataType == 'phrases') {
                        application.contentModelProvider.addChildControl([ '<img class="dataTables_dotbutton" src="/binary/default/desktop/images/dotbutton.png"/>',
                            event.newRowValue.phrase,
                            event.newRowValue.exp ]);
                    } else if(event.dataType == 'words') {
                        application.contentModelProvider.addChildControl([ '<img class="dataTables_dotbutton" src="/binary/default/desktop/images/dotbutton.png"/>',
                            event.newRowValue.word,
                            event.newRowValue.exp,
                            event.newRowValue.frequency ]);
                    }

                    application.reloadManager.changesHappened();
                } else {
                    synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                            if (success) {
                                ;
                            } else {
                                application.reloadManager.performWithoutConfirmation(
                                    synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                );
                            }
                        }
                    );
                }
            }
        );
    }

    this.valueChanged = function (event) {
        var patchInstruction = application.jsonBlueprintFactory.makeBlueprintForObjectType('PatchInstruction');
        patchInstruction.operation = 0; // = SET
        patchInstruction.document = application.jsonBlueprintFactory.makeBlueprintForObjectType('RegularDictionaryConfiguration');
        patchInstruction.document.language = dataProvider.dataProviderState.getRegularDictionaryConfigurationLanguage();
        patchInstruction.document[event.dataType].push(event.newRowValue);

        event.editableHtmlControl.removeClass(application.configuration.newStateClassName);
        event.editableHtmlControl.addClass(application.configuration.editedStateClassName);

        instructionCache.patchActiveRegularDictionary([patchInstruction],
            function (httpCode, xmlHttpRequest, value) {
                if (application.httpCodeManager.successfulRequest(httpCode)) {
                    synchronisationHelper.updateActiveVersion(value);
                    event.sender.getModel().data.rows[event.editedDataRowIndex][event.editedDataColumnIndex] = event.newEditableValue;
                    application.reloadManager.changesHappened();
                } else {
                    synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                        if (success) {
                            /**
                             *  Return to old value if saving fails.
                             */
                            event.editable.text(event.oldEditableValue);

                            event.editableHtmlControl.hideLoadingIndicator();
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

    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'Save':
                if(application.reloadManager.hasChanges()) {
                    instructionCache.flushCache(function (httpCode, xmlHttpRequest, value) {
                        if (application.httpCodeManager.successfulRequest(httpCode)) {
                            application.referenceUpdateManager.updateReferences(xmlHttpRequest.responseText,
                                function(success) {
                                    application.reloadManager.performWithoutConfirmation(
                                        synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                    );
                                });
                        } else {
                            synchronisationHelper.showErrorDialogWithCallback(httpCode, function (success) {
                                /** Saving failed, no reload. */
                                if (success) {
                                    /** NOP */
                                } else {
                                    application.reloadManager.performWithoutConfirmation(
                                        synchronisationHelper.handlePageReload.curry(xmlHttpRequest.responseText)
                                    );
                                }
                            });
                        }
                    });
                }
                break;
            case 'Cancel':
                if(application.reloadManager.hasChanges()) {
                    window.location.reload();
                }
                break;
            case 'PhrasesSelected':
                var state = { dataType:'phrases'};
                $.bbq.pushState(state);
                break;
            case 'WordsSelected':
                var state = { dataType:'words' };
                $.bbq.pushState(state);
                break;
            case 'AddSelected':
                var tableControl = application.contentModelProvider.getTableControl();

                var text = window.lang.convert('ADD_DICTIONARY_ENTRY');

                var formElements = [];
                var col;
                for (var i = 0; i < tableControl.getModel().cols.length; i++) {
                    col = tableControl.getModel().cols[i];

                    var formElement = "";
                    if(i == 1) {
                        formElement += '<input id="auto_to_lower" type="checkbox" name="auto_to_lower" checked="true"/>'
                            + window.lang.convert('TRANSFORM_TO_LOWER') + '<br/>';
                    }

                    formElement += col.title + '<input class="plugintype_input" type="text" name="'
                        + col.context.columnIdentifier + '" /><br/>';

                    if(i == 1) {
                        formElement += '<input id="auto_generate_expression" type="checkbox" name="auto_generate_expression" checked="true"/>'
                            + window.lang.convert('AUTO_GENERATE_EXPRESSION') + '<br/>';
                    }

                    if (col.isServerData) {
                        formElements.push(formElement);
                    }
                }

                var callback = function (success, callbackEvent) {
                    if (success) {
                        var addDictEntryEvent = new Event(tableControl, event.command);
                        addDictEntryEvent.dataType = tableControl.getModel().data.context.dataType;
                        addDictEntryEvent.editableHtmlControl = $('#' + this.CSSClassBase)

                        addDictEntryEvent.newRowValue = application.jsonBlueprintFactory.makeBlueprintForObjectType(tableControl.getModel().data.context.dataType);
                        for (var columnValue in callbackEvent.newRowData) {
                            addDictEntryEvent.newRowValue[columnValue] = application.bindingManager.bindFromString(callbackEvent.newRowData[columnValue]);
                        }
                        instance.addRow(addDictEntryEvent);
                    }
                };

                var model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'), formElements, {dialogType:'table'});

                var dialog = new DialogControl(model);

                dialog.showDialog();
                break;
            case 'DeleteSelected':
                instance.deleteRows(application.contentModelProvider.getTableControl());
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

                    if (event.value.length > 0) {
                        query.params.filter = event.value;
                    } else {
                        delete query.params.filter;
                    }
                    delete query.params.index;
                    delete query.query;
                    delete query.relative;
                    delete query.source;

                    /* Reload the page with the new version active. */
                    window.location.assign($.url.build(query));
                }
                break;
            case 'TableCellEdited':
                instance.valueChanged(event);
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