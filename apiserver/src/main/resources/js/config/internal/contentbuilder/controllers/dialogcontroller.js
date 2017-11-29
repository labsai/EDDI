function InconsistentStateDetectedException(msg) {
    this.message = msg;
}

function DialogController() {
    var updateManager = new DialogUpdateManager(application.dataProvider);

    this.observable = new Observable();

    this.registerEvents = function () {
        var instance = this;

        $('.packagecontrol_sortable_inner, .connector_control_right').sortable({
            cancel: 'a, form',
            connectWith: '.packagecontrol_sortable_inner, .connector_control_right, .negation_control_right',
            containment: '#content',
            placeholder: 'extension_placeholder',
            start: function (event, ui) {
                ui.placeholder.outerHeight(ui.item.outerHeight());
                ui.item.data('old_index', ui.item.index());
            },
            update: function (event, ui) {
                if (this === ui.item.parent()[0]) {
                    ui.item.data("new_index", ui.item.index());

                    var sortableEvent = new SortableEvent('SortUpdatePackageInner');
                    sortableEvent.fromUpdateEvent(this, event, ui);

                    instance.observable.notify(sortableEvent);
                }
            },
            receive: function (eventIn, ui) {
                ui.item.data("new_index", ui.item.index());

                var event = new SortableEvent('SortableReceivedItem');
                event.fromReceiveEvent(this, ui, application.configuration.packageInnerSortableLevel);

                instance.observable.notify(event);
            }
        });

        $('.negation_control_right').sortable({
            cancel: 'a, form',
            connectWith: '.packagecontrol_sortable_inner, .connector_control_right, .negation_control_right',
            containment: '#content',
            placeholder: 'extension_placeholder',
            start: function (event, ui) {
                ui.placeholder.outerHeight(ui.item.outerHeight());
                ui.item.data('old_index', ui.item.index());
            },
            update: function (event, ui) {
                if (this === ui.item.parent()[0]) {
                    ui.item.data("new_index", ui.item.index());

                    var sortableEvent = new SortableEvent('SortUpdatePackageInner');
                    sortableEvent.fromUpdateEvent(this, event, ui);

                    instance.observable.notify(sortableEvent);
                }
            },
            receive: function (eventIn, ui) {
                ui.item.data("new_index", ui.item.index());

                var event = new SortableEvent('SortableReceivedItem');
                event.fromReceiveEvent(this, ui, application.configuration.packageInnerSortableLevel);

                var sortableStr = '#' + $(this).attr('id') + ' > div';
                var numChildren = $(sortableStr).length - 1; // -1 because the new element is already added in the DOM-representation.

                if (numChildren > 0) {
                    var dialogModel = new DialogControlModel(window.lang.convert('ERROR_MAXIMUM_CHILD_COUNT'), function (success) {
                        ;
                    }, window.lang.convert('OK_BUTTON'));
                    var dialogControl = new DialogControl(dialogModel);
                    $(ui.sender).sortable('cancel');

                    dialogControl.showDialog();
                } else {
                    instance.observable.notify(event);
                }
            }
        });
    }
}