function MonitorDescriptionsContentModel(dataProvider, actionHandler) {
    this.defaultTableControlLimit = 10;
    this.defaultTableControlIndex = 0;
    this.defaultTableControlOrder = 'asc';
    this.defaultBotDropDownValue = 'All';
    this.defaultBotVersionDropDownValue = 'All';
    this.defaultConversationStateDropDownValue = 'All';
    this.defaultViewStateDropDownValue = 'All';

    var tableControl;
    var tableControlLimit = this.defaultTableControlLimit;
    var tableControlIndex = this.defaultTableControlIndex;
    var tableControlFilter;
    var tableControlOrder = this.defaultTableControlOrder;
    var botDropDownValue = this.defaultBotDropDownValue;
    var botVersionDropDownValue = this.defaultBotVersionDropDownValue;
    var conversationStateDropDownValue = this.defaultConversationStateDropDownValue;
    var viewStateDropDownValue = this.defaultViewStateDropDownValue;

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

            if (typeof urlParams['botId'] !== 'undefined') {
                botDropDownValue = decodeURIComponent(urlParams['botId']);
            }

            if (typeof urlParams['botVersion'] !== 'undefined') {
                botVersionDropDownValue = decodeURIComponent(urlParams['botVersion']);
            }

            if (typeof urlParams['conversationState'] !== 'undefined') {
                conversationStateDropDownValue = decodeURIComponent(urlParams['conversationState']);
            }

            if (typeof urlParams['viewState'] !== 'undefined') {
                viewStateDropDownValue = decodeURIComponent(urlParams['viewState']);
            }
        }

        var botMaxVersion;

        var botDocumentDescriptions = dataProvider.readDocumentDescriptions('ai.labs.bot', 0, 0, undefined, undefined);
        var botDropDownValues = [];
        botDropDownValues.push(this.defaultBotDropDownValue);
        for (var i = 0; i < botDocumentDescriptions.length; ++i) {
            var botDocumentDescriptionUriObject = SLSUriParser(botDocumentDescriptions[i].resource);
            //botDropDownValues.push({text:botDocumentDescriptions[i].name, value:botDocumentDescriptionUriObject.id});
            botDropDownValues.push(botDocumentDescriptionUriObject.id);

            if (botDocumentDescriptionUriObject.id === botDropDownValue) {
                botMaxVersion = botDocumentDescriptionUriObject.version;
            }
        }

        var botDropDownModel = new DropDownControlModel('dropdown_bots', 'dropdown_container', 'dropdown_dropdown', 'Bot', true, botDropDownValues, botDropDownValue, 'BotDropDownChanged');
        var botDropDownControl = new DropDownControl(botDropDownModel);
        botDropDownControl.observable.addObserver(actionHandler.observer);
        models.push(botDropDownControl);

        if (botDropDownValue !== this.defaultBotDropDownValue) {
            var botVersionDropDownValues = [];
            botVersionDropDownValues.push(this.defaultBotVersionDropDownValue);
            botVersionDropDownValues = botVersionDropDownValues.concat((new Array).arrayWithRange(1, botMaxVersion));

            var botVersionDropDownModel = new DropDownControlModel('dropdown_botversions', 'dropdown_container', 'dropdown_dropdown', 'Bot-Version', true, botVersionDropDownValues, botVersionDropDownValue, 'BotVersionDropDownChanged');
            var botVersionDropDownControl = new DropDownControl(botVersionDropDownModel);
            botVersionDropDownControl.observable.addObserver(actionHandler.observer);
            models.push(botVersionDropDownControl);
        }

        var conversationStateDropDownValues = [];
        conversationStateDropDownValues.push(this.defaultConversationStateDropDownValue);
        conversationStateDropDownValues.push('READY', 'IN_PROGRESS', 'ENDED');

        var conversationStateDropDownModel = new DropDownControlModel('dropdown_conversationstates', 'dropdown_container', 'dropdown_dropdown', 'Conversation-State', true, conversationStateDropDownValues, conversationStateDropDownValue, 'ConversationStateDropDownChanged');
        var conversationStateDropDownControl = new DropDownControl(conversationStateDropDownModel);
        conversationStateDropDownControl.observable.addObserver(actionHandler.observer);
        models.push(conversationStateDropDownControl);

        var viewStateDropDownValues = [];
        viewStateDropDownValues.push(this.defaultViewStateDropDownValue);
        viewStateDropDownValues.push('SEEN', 'UNSEEN');

        var viewStateDropDownModel = new DropDownControlModel('dropdown_viewstates', 'dropdown_container', 'dropdown_dropdown', 'View-State', true, viewStateDropDownValues, viewStateDropDownValue, 'ViewStateDropDownChanged');
        var viewStateDropDownControl = new DropDownControl(viewStateDropDownModel);
        viewStateDropDownControl.observable.addObserver(actionHandler.observer);
        models.push(viewStateDropDownControl);

        var botId;
        if (botDropDownValue != this.defaultBotDropDownValue) {
            botId = botDropDownValue;
        }

        var botVersion;
        if (botVersionDropDownValue != this.defaultBotVersionDropDownValue) {
            botVersion = botVersionDropDownValue;
        }

        var conversationState;
        if (conversationStateDropDownValue != this.defaultConversationStateDropDownValue) {
            conversationState = conversationStateDropDownValue;
        }

        var viewState;
        if (viewStateDropDownValue != this.defaultViewStateDropDownValue) {
            viewState = viewStateDropDownValue;
        }
        var monitorDescriptions = dataProvider.readConversationDescriptors(botId, botVersion, conversationState, viewState, tableControlLimit, tableControlIndex, tableControlFilter, tableControlOrder);

        var tableModel;

        var monitorDescriptionsColumns = [
            new TableControlColumnModel({columnIdentifier: 'username'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_USERNAME'), 'table_col_username', false, false),
            new TableControlColumnModel({columnIdentifier: 'environment'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_ENVIRONMENT'), 'table_col_environment', false, false),
            new TableControlColumnModel({columnIdentifier: 'botname'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_BOTNAME'), 'table_col_botname', false, false),
            new TableControlColumnModel({columnIdentifier: 'lastmodified'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_LASTMODIFIED'), 'table_col_lastmodified', false, false),
            new TableControlColumnModel({columnIdentifier: 'convstepcount'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_CONVSTEPCOUNT'), 'table_col_convstepcount', false, false),
            new TableControlColumnModel({columnIdentifier: 'state'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_STATE'), 'table_col_state', false, false),
            new TableControlColumnModel({columnIdentifier: 'show'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_SHOW'), 'table_col_show', false, false),
            new TableControlColumnModel({columnIdentifier: 'feedback'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_FEEDBACK'), 'table_col_feedback', false, false)
        ];

        var monitorDescriptionsData = [];
        var monitorDescriptionResourceParams = [];
        for (var i = 0; i < monitorDescriptions.length; ++i) {
            var monitorDescriptionUriObject = SLSUriParser(monitorDescriptions[i].resource);
            var lastModifiedDate = new Date(monitorDescriptions[i].lastModifiedOn);
            var lastModifiedDateString = lastModifiedDate.toLocaleDateString() + ' - ' + lastModifiedDate.toLocaleTimeString();
            monitorDescriptionsData.push([monitorDescriptions[i].createdByUserName, monitorDescriptions[i].environment, monitorDescriptions[i].botName, lastModifiedDateString, monitorDescriptions[i].conversationStepSize, monitorDescriptions[i].conversationState, '<a href="' + application.url.getUriForResource(monitorDescriptionUriObject.id) + '">show</a>', monitorDescriptions[i].feedbacks.length]);
            monitorDescriptionResourceParams.push({id: monitorDescriptionUriObject.id});
        }

        tableModel = new TableControlModel(0, 'monitorDescriptions_table_', true, true, monitorDescriptionsColumns, new TableControlDataModel({dataType: 'MonitorDescriptor'}, monitorDescriptionsData, monitorDescriptionResourceParams), tableControlLimit, tableControlIndex, tableControlFilter);

        tableControl = new TableControl(tableModel);
        tableControl.observable.addObserver(actionHandler.observer);
        models.push(tableControl);
        return models;
    }
}