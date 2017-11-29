function TableControl(model) {
    var tableRowSelectedCSSClass = 'table_row_selected';
    var tableRowErrorCSSClass = 'table_row_error';
    var tableRowSuccessCSSClass = 'table_row_success';
    var tableCellErrorCSSClass = 'table_cell_error';
    var tableRowPrefix = model.idPrefix + model.id + '_row_';
    var tableCellPrefix = model.idPrefix + model.id + '_cell_';
    var tableControlLengthId = model.idPrefix + model.id + '_length';
    var tableControlPrevId = model.idPrefix + model.id + '_prev';
    var tableControlNextId = model.idPrefix + model.id + '_next';
    var tableControlSearchTextId = model.idPrefix + model.id + '_searchtext';
    var tableControlSearchButtonId = model.idPrefix + model.id + '_searchbutton';
    this.observable = new Observable();

    this.getModel = function () {
        return model;
    }

    this.addRow = function (data) {
        console.log(data);
        var index = model.data.rows.length;
        var rowRepresentation = '<tr id="' + tableRowPrefix + index + '">'
            + this.createRowContentRepresentation(false, data)
            + '</tr>';

        if ($('#' + model.idPrefix + model.id + ' > tbody > tr').exists()) {
            $('#' + model.idPrefix + model.id + ' > tbody > tr').last().after(rowRepresentation);
        } else {
            $('#' + model.idPrefix + model.id + ' > tbody').html(rowRepresentation);
        }

        $('#' + tableRowPrefix + index).addClass(application.configuration.newStateClassName);

        model.data.rows.push(data);

        this.registerButtonEvents();
    }

    this.getTableRowPrefix = function () {
        return tableRowPrefix;
    }

    this.arrayToHtmlList = function (array) {
        var listRepresentation = '';
        if (array.length > 0) {
            listRepresentation = '<ul>';
            for (var j = 0; j < array.length; j++) {
                var value = array[j];
                if (typeof value === 'string' || value instanceof String) {
                    value = value.replace(/\n/g, '<br/>');
                } else {
                    value = JSON.stringify(value);
                }
                listRepresentation += '<li>' + value + '</li>';
            }
            listRepresentation += '</ul>';
        }
        return listRepresentation;
    }

    this.htmlListToArray = function (htmlString) {
        return this.editableTextToArray(this.htmlListToEditableText(htmlString));
    }

    this.editableTextToArray = function (editableText) {
        return editableText.split('\n\n');
    }

    this.htmlListToEditableText = function (htmlString) {
        var editableText = htmlString;
        editableText = editableText.replace(/<ul>/g, '');
        editableText = editableText.replace(/<li>/g, '');
        editableText = editableText.replace(/<\/li>/g, '\n\n');
        editableText = editableText.replace(/<\/ul>/g, '');
        editableText = editableText.replace(/<br\/>/g, '\n');
        editableText = editableText.replace(/<br>/g, '\n');
        editableText = editableText.substring(0, editableText.lastIndexOf('\n\n'));
        /* Unescape innerHtml before editing. */
        editableText = application.bindingManager.bindToString(editableText);
        return editableText;
    }

    this.editableTextToHtmlList = function (editableText) {
        var array = this.editableTextToArray(editableText);
        var listRepresentation;
        if (array.length > 0) {
            listRepresentation = '<ul>';
            for (var j = 0; j < array.length; j++) {
                listRepresentation += '<li>' + array[j] + '</li>';
            }
            listRepresentation += '</ul>';
        }
        return listRepresentation;
    }

    this.createRowContentRepresentation = function (isHeaderRow, rowData, rowColumnError) {
        var cellTag = 'td';
        if (isHeaderRow) {
            cellTag = 'th';
        }

        var rowRepresentation = '';

        var isColDataTypeArray;
        for (var i = 0; i < model.cols.length; ++i) {
            isColDataTypeArray = false;
            if (typeof rowData !== 'undefined' && typeof rowData[i] !== 'undefined') {
                if ($.isArray(rowData[i])) {
                    isColDataTypeArray = true;
                }
            }

            if (model.cols[i] !== null && typeof model.cols[i] !== 'undefined') {
                rowRepresentation += '<' + cellTag + ' id="' + tableCellPrefix + i + '"';

                var cssClass;

                if (model.cols[i].hasOwnProperty('cssClass')) {
                    cssClass = model.cols[i].cssClass;
                }

                if (model.cols[i].hasOwnProperty('editable')) {
                    if (model.cols[i].editable && !isHeaderRow) {
                        if (typeof cssClass === 'undefined') {
                            cssClass = 'editable';
                        } else {
                            cssClass += ' editable';
                        }

                        if (isColDataTypeArray) {
                            cssClass += 'area';
                        }
                    }
                }

                if (typeof rowColumnError !== 'undefined' && typeof rowColumnError[i] !== 'undefined' && rowColumnError[i]) {
                    if (typeof cssClass === 'undefined') {
                        cssClass = tableCellErrorCSSClass;
                    } else {
                        cssClass += ' ' + tableCellErrorCSSClass;
                    }
                }

                if (typeof cssClass !== 'undefined') {
                    rowRepresentation += ' class="' + cssClass + '"';
                }

                rowRepresentation += '>';

                if (isHeaderRow) {
                    if (model.cols[i].hasOwnProperty('title')) {
                        rowRepresentation += model.cols[i].title;
                    }
                } else {
                    if (typeof rowData[i] !== 'undefined' && rowData[i] != null) {
                        if (isColDataTypeArray) {
                            rowRepresentation += this.arrayToHtmlList(rowData[i]);
                        } else {
                            rowRepresentation += rowData[i].toString();
                        }
                    }
                }

                //if (model.cols[i].hasOwnProperty('width')) {
                //    width = model.cols[i].width;
                //}

                rowRepresentation += '</' + cellTag + '>';
            }
        }

        return rowRepresentation;
    }

    this.createRepresentation = function () {
        var representation = '';
        if (model.showControlHeaders) {
            representation += '<div class="dataTables_wrapper">';
            representation += '<div class="dataTables_length"><label>' + window.lang.convert('TABLE_LENGTH_MENU_PRE') +
                '<span id="' + tableControlLengthId + '" class="dataTables_length_selection"></span>' + window.lang.convert('TABLE_LENGTH_MENU_POST') + '</label></div>';
            representation += '<div class="dataTables_paginate"><a href="#" id="' + tableControlPrevId + '">&lt;</a>   <a href="#" id="' + tableControlNextId + '">&gt;</a></div>';
            var filterValueString = '';
            if (typeof model.filter !== 'undefined') {
                filterValueString = ' value="' + model.filter + '"';
            }
            representation += '<div class="dataTables_filter"><input type="text" id="' + tableControlSearchTextId + '"' + filterValueString + '><input type="button" id="' + tableControlSearchButtonId + '" value="' + window.lang.convert('TABLE_SEARCH') + '"></div>';
            representation += '</div>';
        }

        representation += '<table cellpadding="0" cellspacing="0" border="0" class="display pretty" id="' + model.idPrefix + model.id + '">';

        representation += '<thead><tr>' + this.createRowContentRepresentation(true) + '</tr></thead>';

        representation += '<tbody>';

        for (var i = 0; i < model.data.rows.length; i++) {
            var errorClassHtml = '';
            if (typeof model.data.rowErrors !== 'undefined' && typeof model.data.rowErrors[i] !== 'undefined') {
                errorClassHtml += '" class="';
                if (model.data.rowErrors[i]) {
                    errorClassHtml += tableRowErrorCSSClass;
                } else {
                    errorClassHtml += tableRowSuccessCSSClass;
                }
            }

            var rowColumnError;
            if (typeof model.data.rowColumnErrors !== 'undefined' && model.data.rowColumnErrors.length > i && typeof model.data.rowColumnErrors[i] !== 'undefined') {
                rowColumnError = model.data.rowColumnErrors[i];
            }

            representation += '<tr id="' + tableRowPrefix + i + errorClassHtml + '">' + this.createRowContentRepresentation(false, model.data.rows[i], rowColumnError) + '</tr>';
            if (typeof model.data.detailRowsTableControls !== 'undefined' && typeof model.data.detailRowsTableControls[i] !== 'undefined') {
                representation += '<tr id="' + tableRowPrefix + i + '_detail"><td></td><td colspan="' + (model.cols.length - 1) + '">';
                representation += model.data.detailRowsTableControls[i].createRepresentation();
                representation += '</td></tr>';
            }
        }
        representation += '</tbody>';

        representation += '</table>';

        return representation;
    }

    this.getSelectedRows = function () {
        return $('#' + model.idPrefix + model.id + ' .' + tableRowSelectedCSSClass);
    }

    this.paginateClick = function (instance, tableControlIndex) {
        if (model.index != tableControlIndex) {
            var paginateEvent = new Event(instance, 'IndexChanged');
            paginateEvent.value = tableControlIndex;

            instance.observable.notify(paginateEvent);
        }
    }

    this.registerButtonEvents = function () {
        var instance = this;

        function split(val) {
            return val.split(/,\s*/);
        }

        function extractLast(term) {
            return split(term).pop();
        }

        /* Apply the dropdown handler to the table length control */
        $('#' + tableControlLengthId).dropdown({
            value: model.limit,
            possibleValues: application.configuration.tableControlDefaultLengthValues,
            displayInline: true,
            valueChanged: function (value, oldValue) {
                var lengthChangedEvent = new Event(instance, 'LimitChanged');

                lengthChangedEvent.value = value;
                lengthChangedEvent.oldValue = oldValue;

                instance.observable.notify(lengthChangedEvent);
            }
        });

        $('#' + tableControlPrevId).off('click');
        /* Apply the paginate handler to the table paginate control */
        $('#' + tableControlPrevId).on('click', function () {
            if (typeof model.index !== 'undefined') {
                instance.paginateClick(instance, Math.max(0, model.index - 1));
            }
        });

        $('#' + tableControlNextId).off('click');
        $('#' + tableControlNextId).on('click', function () {
            if (typeof model.index !== 'undefined') {
                instance.paginateClick(instance, model.index + 1);
            }
        });

        var searchEvent = function () {
            var searchText = $('#' + tableControlSearchTextId).val();
            if (typeof searchText !== 'undefined' && searchText.length >= 0) {
                var searchEvent = new Event(instance, 'SearchSelected');
                searchEvent.value = application.bindingManager.bindFromString(searchText);

                instance.observable.notify(searchEvent);
            }
        };

        $('#' + tableControlSearchTextId).unbind('keypress');
        $('#' + tableControlSearchTextId).bind('keypress', function (e) {
            if (e.keyCode == 13) /** ENTER */ {
                searchEvent();
            }
        })

        $('#' + tableControlSearchButtonId).off('click');
        /* Apply the click handler to the table search control */
        $('#' + tableControlSearchButtonId).on('click', searchEvent);

        /* Apply the jEditable handlers to the table */
        var editableFunction = function (value, settings) {
            value = application.bindingManager.bindFromString(value);
            if (settings.type == 'textarea') {
                value = instance.editableTextToArray(value);
            }

            var parentId = $(this).parent().attr("id");
            var editedDataRowIndex;

            if (typeof parentId !== 'undefined' && parentId.indexOf(tableRowPrefix) == 0) {
                editedDataRowIndex = parentId.substring(tableRowPrefix.length, parentId.length);
            }

            /* Identify which dataModel-property is mapped to this cell */
            var id = $(this).attr("id");
            var editedDataColumnIndex;
            if (typeof id !== 'undefined' && id.indexOf(tableCellPrefix) == 0) {
                editedDataColumnIndex = id.substring(tableCellPrefix.length, id.length);
            }

            var editableEvent = new Event(instance, 'TableCellEdited');
            editableEvent.editable = $(this);
            editableEvent.editableHtmlControl = $(this).parent();
            editableEvent.dataType = model.data.context.dataType;

            if (typeof editedDataRowIndex !== 'undefined' && typeof editedDataColumnIndex !== 'undefined') {
                editableEvent.editedDataRowIndex = editedDataRowIndex;
                editableEvent.editedDataColumnIndex = editedDataColumnIndex;

                editableEvent.newRowValue = application.jsonBlueprintFactory.makeBlueprintForObjectType(model.data.context.dataType);
                for (var i = 0; i < model.cols.length; i++) {
                    if (model.cols[i].isServerData) {
                        if (i == editedDataColumnIndex) {
                            editableEvent.newRowValue[model.cols[editedDataColumnIndex].context.columnIdentifier] = value;
                        } else {
                            editableEvent.newRowValue[model.cols[i].context.columnIdentifier] = model.data.rows[editedDataRowIndex][i];
                        }
                    }
                }
                editableEvent.newEditableValue = value;
                editableEvent.oldEditableValue = model.data.rows[editedDataRowIndex][editedDataColumnIndex];

                if (typeof model.data.resourceParams !== 'undefined') {
                    editableEvent.resourceId = model.data.resourceParams[editedDataRowIndex].id;
                    editableEvent.resourceVersion = model.data.resourceParams[editedDataRowIndex].version;
                }
            }

            instance.observable.notify(editableEvent);

            if (settings.type == 'textarea') {
                value = instance.arrayToHtmlList(value);
            }
            return value;
        };

        $('#' + model.idPrefix + model.id + ' .editable').each(function () {
            if (!$(this).hasClass('table_col_expression'))
                $(this).editable(editableFunction, {
                    tooltip: window.lang.convert('EDITABLE_TOOLTIP'),
                    height: "14px",
                    width: "100%",
                    type: 'text',
                    submit: window.lang.convert('EDITABLE_OK'),
                    cancel: window.lang.convert('EDITABLE_CANCEL'),
                    placeholder: window.lang.convert('EDITABLE_PLACEHOLDER'),
                    data: function (value, settings) {
                        /** Unescape innerHtml before editing. */
                        return application.bindingManager.bindToString(value);
                    }
                });
        });

        $('#' + model.idPrefix + model.id + ' tbody .table_col_expression').editable(editableFunction, {
            tooltip: window.lang.convert('EDITABLE_TOOLTIP'),
            height: "14px",
            width: "100%",
            type: 'autocomplete',
            onblur: 'submit',
            data: function (value, settings) {
                /** Unescape innerHtml before editing. */
                return application.bindingManager.bindToString(value);
            },
            autocomplete: {
                options: {
                    source: function (request, response) {
                        var expressions = application.dataProvider.readExpressions(application.dataProvider.dataProviderState.getActiveId(),
                            application.dataProvider.dataProviderState.getActiveVersion(),
                            extractLast(request.term)
                        );

                        response(expressions);
                    },
                    search: function () {
                        // custom minLength
                        var term = extractLast(this.value);
                        if (term.length < 1) {
                            return false;
                        }
                    },
                    focus: function () {
                        // prevent value inserted on focus
                        return false;
                    },
                    select: function (event, ui) {
                        var terms = split(this.value);
                        // remove the current input
                        terms.pop();
                        // add the selected item
                        terms.push(ui.item.value);
                        // add placeholder to get the comma-and-space at the end
                        //terms.push("");
                        this.value = terms.join(", ");
                        return false;
                    },
                    minLength: 1,
                    open: function () {
                        $('.ui-autocomplete-category').next('.ui-menu-item').addClass('ui-first');
                    }
                }
            }
        });


        $('#' + model.idPrefix + model.id + ' .editablearea').editable(editableFunction, {
            tooltip: window.lang.convert('EDITABLE_TOOLTIP'),
            width: "100%",
            type: 'textarea',
            submit: window.lang.convert('EDITABLE_OK'),
            cancel: window.lang.convert('EDITABLE_CANCEL'),
            placeholder: window.lang.convert('EDITABLE_PLACEHOLDER'),
            data: function (value, settings) {
                return instance.htmlListToEditableText(value);
            }
        });

        /* If detail rows are present hide them by default. */
        $('#' + model.idPrefix + model.id + ' tr').each(function (index) {
            if (typeof $(this).attr("id") !== 'undefined' && $(this).attr("id").indexOf("_detail") != -1) {
                $(this).hide();
            }
        });

        $('#' + model.idPrefix + model.id + ' tr').off('click');
        /* Apply the click handlers to the table rows for selecting them. */
        $('#' + model.idPrefix + model.id + ' tr').on('click', function (event) {
            if ($(event.target).hasClass('dataTables_dotbutton')) {
                if (typeof model.data.detailRowsTableControls !== 'undefined') {
                    $('#' + $(this).attr("id") + '_detail', $(this).parent()).toggle();
                } else {
                    console.log(this);
                    $(this).toggleClass(tableRowSelectedCSSClass);
                }
            }

            if ($(event.target).hasClass('tablecontrol_delete')) {
                event.preventDefault();
                var parentId = $(this).attr("id");
                var editedDataRowIndex;

                if (typeof parentId !== 'undefined' && parentId.indexOf(tableRowPrefix) == 0) {
                    editedDataRowIndex = parentId.substring(tableRowPrefix.length, parentId.length);
                }

                var deleteEvent = new Event(instance, 'Delete');
                if (typeof model.data.resourceParams !== 'undefined' && typeof editedDataRowIndex !== 'undefined') {
                    deleteEvent.resourceId = model.data.resourceParams[editedDataRowIndex].id;
                    deleteEvent.resourceVersion = model.data.resourceParams[editedDataRowIndex].version;
                }
                instance.observable.notify(deleteEvent);
            }

            if ($(event.target).hasClass('tablecontrol_run')) {
                event.preventDefault();
                var rowId = $(this).attr("id");
                var selectedDataRowIndex;

                if (typeof rowId !== 'undefined' && rowId.indexOf(tableRowPrefix) == 0) {
                    selectedDataRowIndex = rowId.substring(tableRowPrefix.length, rowId.length);
                }

                var runEvent = new Event(instance, 'Run');
                if (typeof model.data.resourceParams !== 'undefined' && typeof selectedDataRowIndex !== 'undefined') {
                    runEvent.resourceId = model.data.resourceParams[selectedDataRowIndex].id;
                    runEvent.resourceVersion = model.data.resourceParams[selectedDataRowIndex].version;
                    runEvent.rowId = rowId;
                }
                instance.observable.notify(runEvent);
            }
        });
    }
}

function TableControlModel(id, idPrefix, sortable, autoWidth, cols, data, limit, index, filter) {
    this.id = id;
    this.idPrefix = idPrefix;
    this.sortable = sortable;
    this.autoWidth = autoWidth;
    this.cols = cols;
    this.data = data;
    this.limit = limit;
    this.index = index;
    this.filter = filter;
    this.showControlHeaders = true;

    this.setShowControlHeaders = function (value) {
        this.showControlHeaders = value;
    }
}

function TableControlColumnModel(context, title, cssClass, editable, isServerData, width) {
    this.context = context;
    this.title = title;
    this.cssClass = cssClass;
    this.editable = editable;
    this.isServerData = isServerData;

    if (typeof width !== "undefined") {
        this.width = width;
    }
}

function TableControlDataModel(context, rows, resourceParams) {
    this.context = context;
    this.rows = rows;
    this.resourceParams = resourceParams;
    this.detailRowsTableControls;
    this.rowErrors;
    this.rowColumnErrors;

    this.setDetailRowsTableControls = function (value) {
        this.detailRowsTableControls = value;
    }
}