function MonitorDescriptionsContentModel(dataProvider, actionHandler) {
    this.defaultTableControlLimit = 10;
    this.defaultTableControlIndex = 0;
    this.defaultTableControlOrder = 'asc';
    this.defaultBotDropDownValue = 'All';
    this.defaultBotVersionDropDownValue = 'All';
    this.defaultConversationStateDropDownValue = 'All';
    this.defaultViewStateDropDownValue = 'All';

    let tableControl;
    let tableControlLimit = this.defaultTableControlLimit;
    let tableControlIndex = this.defaultTableControlIndex;
    let tableControlFilter;
    let tableControlOrder = this.defaultTableControlOrder;
    let botDropDownValue = this.defaultBotDropDownValue;
    let botVersionDropDownValue = this.defaultBotVersionDropDownValue;
    let conversationStateDropDownValue = this.defaultConversationStateDropDownValue;
    let viewStateDropDownValue = this.defaultViewStateDropDownValue;

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

        let botMaxVersion;

        let botDocumentDescriptions = dataProvider.readDocumentDescriptions('ai.labs.bot', 0, 0, undefined, undefined);
        let botDropDownValues = [];
        botDropDownValues.push(this.defaultBotDropDownValue);
        for (let i = 0; i < botDocumentDescriptions.length; ++i) {
            let botDocumentDescriptionUriObject = SLSUriParser(botDocumentDescriptions[i].resource);
            //botDropDownValues.push({text:botDocumentDescriptions[i].name, value:botDocumentDescriptionUriObject.id});
            botDropDownValues.push(botDocumentDescriptionUriObject.id);

            if (botDocumentDescriptionUriObject.id === botDropDownValue) {
                botMaxVersion = botDocumentDescriptionUriObject.version;
            }
        }

        let botDropDownModel = new DropDownControlModel('dropdown_bots', 'dropdown_container', 'dropdown_dropdown', 'Bot', true, botDropDownValues, botDropDownValue, 'BotDropDownChanged');
        let botDropDownControl = new DropDownControl(botDropDownModel);
        botDropDownControl.observable.addObserver(actionHandler.observer);
        models.push(botDropDownControl);

        if (botDropDownValue !== this.defaultBotDropDownValue) {
            let botVersionDropDownValues = [];
            botVersionDropDownValues.push(this.defaultBotVersionDropDownValue);
            botVersionDropDownValues = botVersionDropDownValues.concat(([]).arrayWithRange(1, botMaxVersion));

            let botVersionDropDownModel = new DropDownControlModel('dropdown_botversions', 'dropdown_container', 'dropdown_dropdown', 'Bot-Version', true, botVersionDropDownValues, botVersionDropDownValue, 'BotVersionDropDownChanged');
            let botVersionDropDownControl = new DropDownControl(botVersionDropDownModel);
            botVersionDropDownControl.observable.addObserver(actionHandler.observer);
            models.push(botVersionDropDownControl);
        }

        let conversationStateDropDownValues = [];
        conversationStateDropDownValues.push(this.defaultConversationStateDropDownValue);
        conversationStateDropDownValues.push('READY', 'IN_PROGRESS', 'ENDED');

        let conversationStateDropDownModel = new DropDownControlModel('dropdown_conversationstates', 'dropdown_container', 'dropdown_dropdown', 'Conversation-State', true, conversationStateDropDownValues, conversationStateDropDownValue, 'ConversationStateDropDownChanged');
        let conversationStateDropDownControl = new DropDownControl(conversationStateDropDownModel);
        conversationStateDropDownControl.observable.addObserver(actionHandler.observer);
        models.push(conversationStateDropDownControl);

        let viewStateDropDownValues = [];
        viewStateDropDownValues.push(this.defaultViewStateDropDownValue);
        viewStateDropDownValues.push('SEEN', 'UNSEEN');

        let viewStateDropDownModel = new DropDownControlModel('dropdown_viewstates', 'dropdown_container', 'dropdown_dropdown', 'View-State', true, viewStateDropDownValues, viewStateDropDownValue, 'ViewStateDropDownChanged');
        let viewStateDropDownControl = new DropDownControl(viewStateDropDownModel);
        viewStateDropDownControl.observable.addObserver(actionHandler.observer);
        models.push(viewStateDropDownControl);

        let botId;
        if (botDropDownValue !== this.defaultBotDropDownValue) {
            botId = botDropDownValue;
        }

        let botVersion;
        if (botVersionDropDownValue !== this.defaultBotVersionDropDownValue) {
            botVersion = botVersionDropDownValue;
        }

        let conversationState;
        if (conversationStateDropDownValue !== this.defaultConversationStateDropDownValue) {
            conversationState = conversationStateDropDownValue;
        }

        let viewState;
        if (viewStateDropDownValue !== this.defaultViewStateDropDownValue) {
            viewState = viewStateDropDownValue;
        }
        let monitorDescriptions = dataProvider.readConversationDescriptors(botId, botVersion, conversationState, viewState, tableControlLimit, tableControlIndex, tableControlFilter, tableControlOrder);

        let tableModel;

        let monitorDescriptionsColumns = [
            new TableControlColumnModel({columnIdentifier: 'username'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_USERNAME'), 'table_col_username', false, false),
            new TableControlColumnModel({columnIdentifier: 'environment'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_ENVIRONMENT'), 'table_col_environment', false, false),
            new TableControlColumnModel({columnIdentifier: 'botname'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_BOTNAME'), 'table_col_botname', false, false),
            new TableControlColumnModel({columnIdentifier: 'lastmodified'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_LASTMODIFIED'), 'table_col_lastmodified', false, false),
            new TableControlColumnModel({columnIdentifier: 'convstepcount'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_CONVSTEPCOUNT'), 'table_col_convstepcount', false, false),
            new TableControlColumnModel({columnIdentifier: 'state'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_STATE'), 'table_col_state', false, false),
            new TableControlColumnModel({columnIdentifier: 'show'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_SHOW'), 'table_col_show', false, false),
            new TableControlColumnModel({columnIdentifier: 'feedback'}, window.lang.convert('MONITOR_DESCRIPTOR_TABLE_COL_FEEDBACK'), 'table_col_feedback', false, false)
        ];

        let monitorDescriptionsData = [];
        let monitorDescriptionResourceParams = [];
        for (let i = 0; i < monitorDescriptions.length; ++i) {
            let monitorDescriptionUriObject = SLSUriParser(monitorDescriptions[i].resource);
            let lastModifiedDate = new Date(monitorDescriptions[i].lastModifiedOn);
            let lastModifiedDateString = lastModifiedDate.toLocaleDateString() + ' - ' + lastModifiedDate.toLocaleTimeString();
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