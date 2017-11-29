function DialogHeaderModel() {
    this.observable = new Observable();

    this.getHeaderModel = function () {
        var model = [];

        model.push(new HeaderElement(window.lang.convert('CONTEXT_SAVE'), function () {
            this.observable.notify(new Event(this, 'Save'));
        }, this, 'iconimage_plus'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_CANCEL'), function () {
            this.observable.notify(new Event(this, 'Cancel'));
        }, this, 'iconimage_cross'));

        model.push(new HeaderElement(window.lang.convert('CONTEXT_ADD_COLUMN'), function () {
            this.observable.notify(new Event(this, 'AddColumn'));
        }, this, 'iconimage_plus'));

        /**model.push(new HeaderElement(window.lang.convert('CONTEXT_CHECK_FOR_ERROR'), function() {
         this.observable.notify(new Event(this, 'CheckError'));
         }, this));*/

        return model;
    }
}