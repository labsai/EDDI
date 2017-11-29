function DictionaryContentModel(dataProvider, actionHandler) {
    this.defaultTableControlLimit = 10;
    this.defaultTableControlIndex = 0;
    this.defaultTableControlOrder = 'asc';

    var dictionary;

    var tableControl;
    var tableControlLimit = this.defaultTableControlLimit;
    var tableControlIndex = this.defaultTableControlIndex;
    var tableControlFilter;
    var tableControlOrder = this.defaultTableControlOrder;

    this.getTableControl = function () {
        return tableControl;
    }

    var firstRun = true;
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

        //Hash's (Fragment's)
        var hashString = $.param.fragment();

        if (typeof dictionary === 'undefined') {
            dictionary = dataProvider.readActiveRegularDictionary(tableControlLimit,
                tableControlIndex,
                tableControlFilter,
                tableControlOrder);
        }

        if (firstRun) {
            application.contentModelHelper.createDocumentDescriptorDisplayControl();
            application.contentModelHelper.createResourceVersionSelectorControl();
            application.contentModelHelper.createReturnToParentButton();
            application.contentModelHelper.createLanguageSelector();

            firstRun = false;
        }

        var tableModel;

        if (hashString.indexOf("dataType=words") != -1) {
            //words
            var wordsColumns = [
                new TableControlColumnModel({columnIdentifier: 'selection'},
                    window.lang.convert('DICTIONARY_TABLE_COL_SELECTION'),
                    'table_col_selection', false, false),
                new TableControlColumnModel({columnIdentifier: 'word'},
                    window.lang.convert('DICTIONARY_TABLE_COL_ENTRY'),
                    'table_col_entry', false, true),
                new TableControlColumnModel({columnIdentifier: 'exp'},
                    window.lang.convert('DICTIONARY_TABLE_COL_EXPRESSION'),
                    'table_col_expression', true, true),
                new TableControlColumnModel({columnIdentifier: 'frequency'},
                    window.lang.convert('DICTIONARY_TABLE_COL_FREQUENCY'),
                    'table_col_frequency', true, true)
            ];

            var wordsData = [];
            for (var i = 0; i < dictionary.words.length; ++i) {
                wordsData.push(['<img class="dataTables_dotbutton" src="/binary/img/config/dotbutton.png"/>',
                    dictionary.words[i].word,
                    dictionary.words[i].exp,
                    dictionary.words[i].frequency]);
            }

            tableModel = new TableControlModel(0, 'dictionary_table_',
                true, true,
                wordsColumns,
                new TableControlDataModel({dataType: 'words'}, wordsData),
                tableControlLimit, tableControlIndex, tableControlFilter);
        } else {
            //phrases
            var phrasesColumns = [
                new TableControlColumnModel({columnIdentifier: 'selection'},
                    window.lang.convert('DICTIONARY_TABLE_COL_SELECTION'),
                    'table_col_selection', false, false),
                new TableControlColumnModel({columnIdentifier: 'phrase'},
                    window.lang.convert('DICTIONARY_TABLE_COL_ENTRY'),
                    'table_col_entry', false, true),
                new TableControlColumnModel({columnIdentifier: 'exp'},
                    window.lang.convert('DICTIONARY_TABLE_COL_EXPRESSION'),
                    'table_col_expression', true, true)
            ];

            var phrasesData = [];
            for (var i = 0; i < dictionary.phrases.length; ++i) {
                phrasesData.push(['<img class="dataTables_dotbutton" src="/binary/img/config/dotbutton.png"/>',
                    dictionary.phrases[i].phrase,
                    dictionary.phrases[i].exp]);
            }

            tableModel = new TableControlModel(0, 'dictionary_table_',
                true, true, phrasesColumns,
                new TableControlDataModel({dataType: 'phrases'}, phrasesData),
                tableControlLimit, tableControlIndex, tableControlFilter);

        }
        tableControl = new TableControl(tableModel);
        tableControl.observable.addObserver(actionHandler.observer);
        models.push(tableControl);
        return models;
    }

    this.urlHashHasChanged = function (contentBuilder) {
        var contentModel = this.makeContentModel();

        contentBuilder.buildContent(contentModel);
        contentBuilder.registerEvents();
    }

    this.addChildControl = function (data) {
        tableControl.addRow(data);
    }
}