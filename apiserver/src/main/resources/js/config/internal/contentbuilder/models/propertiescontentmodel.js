function PropertiesContentModel(dataProvider, actionHandler) {
    this.makeContentModel = function (sizeCallbackInstance, selectionCallbackInstance) {
        let models = [];

        let columns = [
            new TableControlColumnModel("Entry"),
            new TableControlColumnModel("Expression")
        ];

        let phrases = dataProvider.readActiveRegularDictionary('package1', 'ai.labs.common.english').phrases;

        let phrasesData = [];
        for (let i = 0; i < phrases.length; ++i) {
            phrasesData.push([phrases[i].phrase, phrases[i].exp]);
        }

        console.log(dataProvider.readActiveRegularDictionary('package1', 'ai.labs.common.english'));

        let tableModel = new TableControlModel(0, 'dictionary_table_', true, true, columns, phrasesData);
        let table = new TableControl(tableModel);
        models.push(table);

        return models;
    }
}