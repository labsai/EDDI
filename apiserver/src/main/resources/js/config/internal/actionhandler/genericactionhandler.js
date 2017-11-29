function GenericActionHandler() {
    this.handleDelete = function (event, deleteCallback) {
        var model;
        if (event.sender instanceof GroupControl && event.sender.getModel().text === application.configuration.lostAndFoundGroupName) {
            var text = window.lang.convert('CANNOT_DELETE_GROUP_LOST_AND_FOUND');
            var callback = function () {
                ;
            };

            model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), false, false);
        } else {
            var text;

            if (event.sender instanceof GroupControl) {
                text = window.lang.convert('ASK_DELETE_GROUP') + '<b>' + event.sender.getModel().text + '</b>?</p><br/>' + window.lang.convert('WARN_DELETE_GROUP');
            } else {
                if (event.sender instanceof ConnectorControl) {
                    text = window.lang.convert('ASK_DELETE_CONNECTOR') + '<br/>' + window.lang.convert('WARN_DELETE_GROUP');
                } else {
                    text = window.lang.convert('ASK_DELETE_ELEMENT');
                }
            }

            var callback = function (success) {
                if (success) {
                    var newEvent = jQuery.extend({}, event);

                    newEvent.command = 'ControlDeleted';
                    newEvent.firstCommand = event.command;

                    deleteCallback(newEvent);
                }
            };

            model = new DialogControlModel(text, callback, window.lang.convert('OK_BUTTON'), window.lang.convert('CANCEL_BUTTON'), false);
        }

        var dialog = new DialogControl(model);
        dialog.showDialog();
    }

    this.handleAddChild = function (event, contentModel) {
        contentModel.addChildControl(event.sender);
    }

    this.handleRemoveChild = function (event, contentModel) {
        contentModel.removeChildControl(event.sender);
    }
}