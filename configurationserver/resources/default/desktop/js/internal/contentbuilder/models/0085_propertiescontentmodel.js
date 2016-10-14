function PropertiesContentModel(dataProvider, actionHandler) {
    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        var models = [];

        var columns = [
            new TableControlColumnModel("Entry"),
            new TableControlColumnModel("Expression")
        ];

        var phrases = dataProvider.readActiveRegularDictionary('package1', 'io.sls.common.english').phrases;

        var phrasesData = [];
        for (var i = 0; i < phrases.length; ++i) {
            phrasesData.push([ phrases[i].phrase, phrases[i].exp ]);
        }

        console.log(dataProvider.readActiveRegularDictionary('package1', 'io.sls.common.english'));

        var tableModel = new TableControlModel(0, 'dictionary_table_', true, true, columns, phrasesData);
        var table = new TableControl(tableModel);
        models.push(table);

        return models;
    }
}