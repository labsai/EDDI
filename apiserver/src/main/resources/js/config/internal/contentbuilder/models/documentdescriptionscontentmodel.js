function DocumentDescriptionsContentModel(dataProvider, actionHandler, page) {
    this.defaultTableControlLimit = 10;
    this.defaultTableControlIndex = 0;
    this.defaultTableControlOrder = 'asc';

    var documentDescriptions;

    var tableControl;
    var tableControlLimit = this.defaultTableControlLimit;
    var tableControlIndex = this.defaultTableControlIndex;
    var tableControlFilter;
    var tableControlOrder = this.defaultTableControlOrder;

    this.getTableControl = function () {
        return tableControl;
    }

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        var models = [];

        var urlParams = $.url.parse(window.location.href).params;
        if (typeof urlParams !== 'undefined') {
            if (typeof urlParams['limit'] !== 'undefined') {
                tableControlLimit = parseInt(decodeURIComponent(urlParams['limit'])) || this.defaultTableControlLimit;
            }

            if (typeof urlParams['index'] !== 'undefined') {
                tableControlIndex = parseInt(decodeURIComponent(urlParams['index'])) || this.defaultTableControlIndex;
            }

            if (typeof urlParams['filter'] !== 'undefined') {
                tableControlFilter = decodeURIComponent(urlParams['filter']);
            }

            if (typeof urlParams['order'] !== 'undefined') {
                tableControlOrder = decodeURIComponent(urlParams['order']);
            }
        }

        var documentDescriptionType;
        switch (page) {
            case 'bots':
                documentDescriptionType = 'ai.labs.bot';
                break;
            case 'packages':
                documentDescriptionType = 'ai.labs.package';
                break;
            case 'outputs':
                documentDescriptionType = 'ai.labs.output';
                break;
            case 'dialogs':
                documentDescriptionType = 'ai.labs.behavior';
                break;
            case 'dictionaries':
                documentDescriptionType = 'ai.labs.regulardictionary';
                break;
            default:
//                throw new MalformedURLException('Navigation error: No such page: ' + page);
                break;
        }

        documentDescriptions = dataProvider.readDocumentDescriptions(documentDescriptionType, tableControlLimit, tableControlIndex, tableControlFilter, tableControlOrder);

        var tableModel;

        var documentDescriptionsColumns = [
            new TableControlColumnModel({columnIdentifier: 'name'}, window.lang.convert('DICTIONARY_TABLE_COL_NAME'), 'table_col_name', true, true),
            new TableControlColumnModel({columnIdentifier: 'edit'}, window.lang.convert('DICTIONARY_TABLE_COL_EDIT'), 'table_col_edit', false, false),
            new TableControlColumnModel({columnIdentifier: 'delete'}, window.lang.convert('DICTIONARY_TABLE_COL_DELETE'), 'table_col_delete', false, false)
        ];

        var documentDescriptionsData = [];
        var documentDescriptionResourceParams = [];
        for (var i = 0; i < documentDescriptions.length; ++i) {
            var botDocumentDescriptionUriObject = SLSUriParser(documentDescriptions[i].resource);
            documentDescriptionsData.push([documentDescriptions[i].name, '<a href="' + application.url.getUriForResource(botDocumentDescriptionUriObject.id, botDocumentDescriptionUriObject.version) + '">' + window.lang.convert('DICTIONARY_TABLE_COL_EDIT') + '</a>', '<a href="#" class="tablecontrol_delete" >' + window.lang.convert('DICTIONARY_TABLE_COL_DELETE') + '</a>']);
            documentDescriptionResourceParams.push({
                id: botDocumentDescriptionUriObject.id,
                version: botDocumentDescriptionUriObject.version
            });
        }

        tableModel = new TableControlModel(0, 'documentDescription_table_', true, true, documentDescriptionsColumns, new TableControlDataModel({dataType: 'SimpleDocumentDescriptor'}, documentDescriptionsData, documentDescriptionResourceParams), tableControlLimit, tableControlIndex, tableControlFilter);

        tableControl = new TableControl(tableModel);
        tableControl.observable.addObserver(actionHandler.observer);
        models.push(tableControl);

        application.contentModelHelper.createLanguageSelector();

        return models;
    }
}