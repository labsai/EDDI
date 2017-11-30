function OutputContentModel(dataProvider, actionHandler) {
    this.defaultTableControlLimit = 10;
    this.defaultTableControlIndex = 0;
    this.defaultTableControlOrder = 'asc';

    let tableControl;
    let tableControlLimit = this.defaultTableControlLimit;
    let tableControlIndex = this.defaultTableControlIndex;
    let tableControlFilter;
    let tableControlOrder = this.defaultTableControlOrder;

    this.getTableControl = function () {
        return tableControl;
    };

    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        let models = [];

        let urlParams = $.url.parse(window.location.href).params;
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

        let output = dataProvider.readActiveOutputSet(tableControlLimit, tableControlIndex, tableControlFilter, tableControlOrder);

        application.contentModelHelper.createDocumentDescriptorDisplayControl();
        application.contentModelHelper.createResourceVersionSelectorControl();
        application.contentModelHelper.createReturnToParentButton();
        application.contentModelHelper.createLanguageSelector();

        let outputSetColumns = [
            new TableControlColumnModel({columnIdentifier: 'selection'}, window.lang.convert('DICTIONARY_TABLE_COL_SELECTION'), 'table_col_selection', false, false),
            new TableControlColumnModel({columnIdentifier: 'action'}, window.lang.convert('DICTIONARY_TABLE_COL_ENTRY'), 'table_col_entry', false, true),
            new TableControlColumnModel({columnIdentifier: 'timesOccurred'}, window.lang.convert('DICTIONARY_TABLE_COL_OCCURRENCE'), 'table_col_occurrence', false, true),
            new TableControlColumnModel({columnIdentifier: 'outputs'}, window.lang.convert('DICTIONARY_TABLE_COL_OUTPUTVALUES'), 'table_col_outputvalues', true, true)
        ];

        let outputSetData = [];
        for (let i = 0; i < output.outputSet.length; ++i) {
            outputSetData.push(['<img class="dataTables_dotbutton" src="/binary/img/config/dotbutton.png"/>', output.outputSet[i].action, output.outputSet[i].timesOccurred, output.outputSet[i].outputs[0].valueAlternatives]);
        }

        let tableModel = new TableControlModel(0, 'dictionary_table_', true, true, outputSetColumns, new TableControlDataModel({dataType: 'outputs'}, outputSetData), tableControlLimit, tableControlIndex, tableControlFilter);

        tableControl = new TableControl(tableModel);
        tableControl.observable.addObserver(actionHandler.observer);
        models.push(tableControl);
        return models;
    };

    this.addChildControl = function (data) {
        tableControl.addRow(data);
    }
}