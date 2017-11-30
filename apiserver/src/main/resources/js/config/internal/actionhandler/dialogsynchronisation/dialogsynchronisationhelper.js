function DialogSynchronisationHelper(dataProvider) {
    this.removeSenderFromDOM = function (event, animated) {
        event.sender.observable.notify(new Event(event.sender, 'HasBeenRemoved'));

        if (typeof animated !== 'undefined') {
            if (!animated) {
                $('#' + event.sender.getModel().idPrefix + event.sender.getModel().id).remove();
                return;
            }
        }

        // Remove from DOM after data provider call.
        $('#' + event.sender.getModel().idPrefix + event.sender.getModel().id).stop().fadeOut('slow', function () {
            $('#' + event.sender.getModel().idPrefix + event.sender.getModel().id).remove();
        });
    };

    this.removeSenderFromControlTree = function (event) {
        event.parent.getModel().children.removeElement(event.sender);
    };

    this.showErrorDialogWithCallback = function (httpCode, callback) {
        let text;
        switch (httpCode) {
            case 409:
                text = window.lang.convert('HTTP_ERROR_409_CONFLICT');
                break;
            default:
                text = window.lang.convert('HTTP_ERROR_DEFAULT');
                break;
        }

        text += ' (' + window.lang.convert('ERRORCODE_DESCRIPTOR') + ' ' + httpCode + ')';

        let dialogModel;

        if (httpCode === 409) {
            dialogModel = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('RELOAD_BUTTON'));
        } else {
            dialogModel = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'));
        }

        let dialogControl = new DialogControl(dialogModel);

        dialogControl.showDialog();
    };

    this.handlePageReload = function (value) {
        let query = $.url.parse(window.location.href);

        let newVersion = $.url.parse(value).params.version;

        query.params.version = newVersion;
        delete query.query;
        delete query.relative;
        delete query.source;

        /** Reload the page with the new version active. */
        window.location.assign($.url.build(query));
    };

    this.updateActiveVersion = function (value) {
        let query = $.url.parse(value);

        dataProvider.setActiveVersion(query.params['version']);
    }
}