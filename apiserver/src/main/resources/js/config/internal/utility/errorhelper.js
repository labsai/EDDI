function ErrorHelper() {
    this.showError = function (params) {
        let text = window.lang.convert('ERROR_GENERAL');
        let callback = function (success) {
            if (success) {
                window.location.assign(application.url.getUriForPage(application.url.getCurrentPage()));
            }
        };

        if (typeof params !== 'undefined') {
            if (params.text !== 'undefined') {
                text = params.text;
            }

            if (params.$callback) {
                callback = params.$callback;
            }
        }

        let dialogModel = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'));

        let dialogControl = new DialogControl(dialogModel);

        dialogControl.showDialog();
    }
}