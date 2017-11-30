function PackageController() {
    this.observable = new Observable();

    this.registerEvents = function () {
        let instance = this;

        $('.lifecyclegroup_sortable_inner').sortable({
            cancel: 'a, form',
            containment: '#content',
            placeholder: 'extension_placeholder',
            start: function (event, ui) {
                ui.placeholder.outerHeight(ui.item.outerHeight());
                ui.item.data('old_index', ui.item.index());
            },
            update: function (event, ui) {
                ui.item.data("new_index", ui.item.index());

                let sortableEvent = new Event(this, 'SortUpdatePackageInner');
                sortableEvent.originalEvent = event;
                sortableEvent.ui = ui;
                sortableEvent.receiver = $(this);

                instance.observable.notify(sortableEvent);
            },
            receive: function (eventIn, ui) {
                ui.item.data("new_index", ui.item.index());

                let event = new SortableEvent('SortableReceivedItem');
                event.fromReceiveEvent(this, ui, application.configuration.packageInnerSortableLevel);

                instance.observable.notify(event);
            }
        });
    }
}