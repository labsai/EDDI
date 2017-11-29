function DictionaryHeaderModel() {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        var model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_SAVE'), function () {
            this.observable.notify(new Event(this, 'Save'));
        }, this, 'iconimage_plus'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_CANCEL'), function () {
            this.observable.notify(new Event(this, 'Cancel'));
        }, this, 'iconimage_cross'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_PHRASES'), function () {
            this.observable.notify(new Event(this, 'PhrasesSelected'));
        }, this));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_WORDS'), function () {
            this.observable.notify(new Event(this, 'WordsSelected'));
        }, this));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_ADD'), function () {
            this.observable.notify(new Event(this, 'AddSelected'));
        }, this, 'iconimage_plus'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_DELETE'), function () {
            this.observable.notify(new Event(this, 'DeleteSelected'));
        }, this, 'iconimage_cross'));

        return model;
    }
}