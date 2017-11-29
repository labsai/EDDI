function TestCaseDescriptionsContentModel(dataProvider, actionHandler) {
    this.defaultTableControlLimit = 10;
    this.defaultTableControlIndex = 0;
    this.defaultTableControlOrder = 'asc';

    var tableControl;
    var tableControlLimit = this.defaultTableControlLimit;
    var tableControlIndex = this.defaultTableControlIndex;
    var tableControlFilter;
    var tableControlOrder = this.defaultTableControlOrder;
    var botId;
    var botVersion;

    this.getTableControl = function () {
        return tableControl;
    }

    this.setTestCaseResultState = function (rowId, state) {
        $('.table_col_state', $('#' + rowId)).text(state);
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

            if (typeof urlParams['botId'] !== 'undefined') {
                botId = decodeURIComponent(urlParams['botId']);
            }

            if (typeof urlParams['botVersion'] !== 'undefined') {
                botVersion = decodeURIComponent(urlParams['botVersion']);
            }
        }

        var testCaseDescriptors = dataProvider.readTestCaseDescriptors(botId, botVersion, tableControlLimit, tableControlIndex, tableControlFilter, tableControlOrder);

        var tableModel;

        var testCaseDescriptorsColumns = [
            new TableControlColumnModel({columnIdentifier: 'name'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_NAME'), 'table_col_name', true, true),
            /*new TableControlColumnModel({columnIdentifier:'lastModifiedBy'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_LAST_MODIFIED_BY'), 'table_col_last_modified_by', false, false),*/
            new TableControlColumnModel({columnIdentifier: 'created'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_CREATED'), 'table_col_created', false, false),
            new TableControlColumnModel({columnIdentifier: 'lastModified'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_LAST_MODIFIED'), 'table_col_last_modified', false, false),
            /*new TableControlColumnModel({columnIdentifier:'lastRun'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_LAST_RUN'), 'table_col_last_run', false, false),*/
            new TableControlColumnModel({columnIdentifier: 'state'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_STATE'), 'table_col_state', false, false),
            new TableControlColumnModel({columnIdentifier: 'show'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_SHOW'), 'table_col_show', false, false),
            new TableControlColumnModel({columnIdentifier: 'delete'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_DELETE'), 'table_col_delete', false, false),
            new TableControlColumnModel({columnIdentifier: 'run'}, window.lang.convert('TESTCASEDESCRIPTION_TABLE_COL_RUN'), 'table_col_run', false, false)
        ];

        var testCaseDescriptorsData = [];
        var testCaseDescriptorsResourceParams = [];
        for (var i = 0; i < testCaseDescriptors.length; ++i) {
            var testCaseDescriptorUriObject = SLSUriParser(testCaseDescriptors[i].resource);
            var createdOnDate = new Date(testCaseDescriptors[i].createdOn);
            var createdOnDateString = createdOnDate.toLocaleDateString() + ' - ' + createdOnDate.toLocaleTimeString();
            testCaseDescriptorsData.push([testCaseDescriptors[i].name, createdOnDateString, /*testCaseDescriptors[i].lastModifiedBy,*/ (new Date(testCaseDescriptors[i].lastModifiedOn)).toLocaleTimeString(), /*(new Date(testCaseDescriptors[i].lastRun)).toLocaleTimeString(),*/ testCaseDescriptors[i].testCaseState, '<a href="' + application.url.getUriForResource(testCaseDescriptorUriObject.id) + '">show</a>', '<a href="#" class="tablecontrol_delete" >delete</a>', '<a href="#" class="tablecontrol_run" >run</a>']);
            testCaseDescriptorsResourceParams.push({
                id: testCaseDescriptorUriObject.id,
                version: testCaseDescriptorUriObject.version
            });
        }

        tableModel = new TableControlModel(0, 'testCaseDescriptors_table_', true, true, testCaseDescriptorsColumns, new TableControlDataModel({dataType: 'TestCaseDescriptor'}, testCaseDescriptorsData, testCaseDescriptorsResourceParams), tableControlLimit, tableControlIndex, tableControlFilter);

        tableControl = new TableControl(tableModel);
        tableControl.observable.addObserver(actionHandler.observer);
        models.push(tableControl);
        return models;
    }
}