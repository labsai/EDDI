function VersionHelper() {
    this.gotoResourceUri = function (uri) {
        /*
        var callback = function (success) {
            if (success) {
                window.location.assign(application.url.getEditorUriForResourceUri(uri));
            } else {

            }
        };

        $.modal.close();

        var dialogModel = new DialogControlModel(window.lang.convert('CONFIRM_RELOAD'),
            callback, window.lang.convert('RELOAD_BUTTON'), window.lang.convert('CANCEL_BUTTON'), false, false);
        var dialogControl = new DialogControl(dialogModel);

         //Ask for confirmation.
        dialogControl.showDialog(); */
        window.location.assign(application.url.getEditorUriForResourceUri(uri));
    }
}