function SortableEvent(command) {
    this.command = command;

    this.fromReceiveEvent = function (that, ui, levelIdentifier) {
        var htmlIdResolver = new HTMLIDResolver();

        var senderId = ui.sender.attr('id');
        var receiverId = $(that).attr('id');
        var itemId = ui.item.attr('id');

        try {
            this.sender = htmlIdResolver.resolveId(senderId);
            this.receiver = htmlIdResolver.resolveId(receiverId);
            this.item = htmlIdResolver.resolveId(itemId);
        } catch (ex) {
            if (ex instanceof NoSuchIdException) {
                console.log(ex.message);
                return;
            } else {
                /** Propagate other exception types. */
                throw ex;
            }
        }

        this.sortableLevel = levelIdentifier;
        this.oldIndex = ui.item.data('old_index');
        this.newIndex = ui.item.data('new_index');
        this.sortable = ui.sender;

        return this;
    }

    this.fromUpdateEvent = function(that, event, ui) {
        this.sender = that;
        this.originalEvent = event;
        this.ui = ui;
        var idResolver = new HTMLIDResolver();
        try {
            this.receiver = idResolver.resolveId($(that).attr('id'));
        } catch(ex) {
            if(ex instanceof NoSuchIdException) {
                this.receiver = application.configuration.rootElementReceiver;
            } else {
                throw ex;
            }
        }

        var itemId = ui.item.attr('id');
        this.item = idResolver.resolveId(itemId);

        this.oldIndex = ui.item.data('old_index');
        this.newIndex = ui.item.data('new_index');

        return this;
    }
}