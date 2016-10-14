/**
 * Dynamic content creation.
 *
 * @author Patrick Schwab
 */

/**
 * The ContentBuilder is responsible for creating and handling the content section from supplied data models.
 *
 * @constructor
 */
function ContentBuilder(viewController) {
    this.observable = new Observable();

    /**
     * Stores all active group control instances.
     *
     * @type {Array}
     */
    var groupControls = [];

    this.getControls = function () {
        return groupControls;
    }

    this.addGroupControl = function (control) {
        groupControls.push(control);
        this.readjustSize();
    }

    var instance = this;

    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'SizeChanged':
                instance.readjustSize();
                break;
            case 'GroupSelected':
                instance.handleSelection(event.sender);
                break;
        }
    });

    this.rebuildContent = function () {
        this.buildContent(groupControls);
    }

    this.buildContent = function (controls) {
        groupControls = controls;

        var sortableOuter = '';
        if (groupControls.length > 0) {
            if (groupControls[0] instanceof GroupControl) {
                sortableOuter = ' class="sortable_outer"';
            }
        }

        var fullHtml = '<div id="content"><div id="toplevel"' + sortableOuter + '>';

        for (var i = 0; i < groupControls.length; ++i) {
            fullHtml = fullHtml + groupControls[i].createRepresentation();
        }

        fullHtml += '</div></div>';

        $('#content').replaceWith('<body>' + fullHtml + '</body>');

        var setDynamicWorkspaceLayout = function() {
            $('#content').height($('#right').height() - $('#header').height());
        }

        setDynamicWorkspaceLayout();

        $(window).resize(function(){
            setDynamicWorkspaceLayout();
        });
    }

    this.registerEvents = function () {
        this.readjustSize();

        /** Note: It's important to register button events just after the DOM-changes. */
        for (var i = 0; i < groupControls.length; ++i) {
            groupControls[i].registerButtonEvents();
        }
    }

    this.readjustSize = function () {
        var requiredWidth = function () {
            var retVal = 0;

            for (var i = 0; i < groupControls.length; ++i) {
                if (groupControls[i].hasOwnProperty('getWidth')) {
                    retVal += groupControls[i].getWidth();
                }
            }

            /** Add right side padding. */
            retVal += 15;

            return retVal;
        }();

        var contentWidth = $(window).outerWidth(true) - $('#mainmenu').outerWidth(true) - 1;

        /** Must specifically supply a width to make the div grow vertically instead of horizontally. */
        if (requiredWidth > contentWidth) {
            $('.sortable_outer').width(requiredWidth);
        } else {
            $('.sortable_outer').width(contentWidth);
        }

        var instance = this;

        $('.sortable_outer').sortable({cancel:'a, .groupcontrol_sortable_inner, form',
            containment:'#content',
            start:function (event, ui) {
                ui.placeholder.outerHeight(ui.item.outerHeight());
                ui.item.data('old_index', ui.item.index());
            },
            update:function (event, ui) {
                ui.item.data("new_index", ui.item.index());

                var sortableEvent = new SortableEvent('SortUpdatePackageInner');
                sortableEvent.fromUpdateEvent(this, event, ui);

                instance.observable.notify(sortableEvent);
            }});

        $('.sortable_outer .groupcontrol_closed').live('mouseover', function () {
            $('.sortable_outer').sortable('option', 'placeholder', 'groupcontrol_closed_placeholder')
        });

        $('.sortable_outer .groupcontrol_opened').live('mouseover', function () {
            $('.sortable_outer').sortable('option', 'placeholder', 'groupcontrol_opened_placeholder')
        });

        $('.groupcontrol_sortable_inner').sortable({cancel:'a, form',
            connectWith:'.groupcontrol_sortable_inner',
            containment:'#content',
            start:function (event, ui) {
                ui.placeholder.outerHeight(ui.item.outerHeight());
                ui.item.data('old_index', ui.item.index());
            },
            receive:function (eventIn, ui) {
                ui.item.data("new_index", ui.item.index());

                var event = new SortableEvent('SortableReceivedItem');
                event.fromReceiveEvent(this, ui, application.configuration.packageOuterSortableLevel);

                instance.observable.notify(event);
            },
            update:function (event, ui) {
                if (this === ui.item.parent()[0]) {
                    ui.item.data("new_index", ui.item.index());

                    var sortableEvent = new SortableEvent('SortUpdatePackageInner');
                    sortableEvent.fromUpdateEvent(this, event, ui);

                    instance.observable.notify(sortableEvent);
                }
            }});

        $('.groupcontrol_sortable_inner .packagecontrol_closed').live('mouseover', function () {
            $('.groupcontrol_sortable_inner').sortable('option', 'placeholder', 'packagecontrol_closed_placeholder')
        });

        $('.groupcontrol_sortable_inner .packagecontrol_opened').live('mouseover', function () {
            $('.groupcontrol_sortable_inner').sortable('option', 'placeholder', 'packagecontrol_opened_placeholder')
        });

        $('.groupcontrol_sortable_inner .documentdescriptioncontrol_closed').live('mouseover', function () {
            $('.groupcontrol_sortable_inner').sortable('option', 'placeholder', 'documentdescriptioncontrol_closed_placeholder')
        });

        $('.groupcontrol_sortable_inner .documentdescriptioncontrol_opened').live('mouseover', function () {
            $('.groupcontrol_sortable_inner').sortable('option', 'placeholder', 'documentdescriptioncontrol_opened_placeholder')
        });

        if (viewController !== null && typeof viewController !== 'undefined') {
            viewController.registerEvents();
        }
    }

    this.getSelectedGroupControl = function () {
        for (var i = 0; i < groupControls.length; ++i) {
            for (var j = 0; j < groupControls[i].getModel().children.length; ++j) {
                if (groupControls[i].getModel().children[j].getModel().selected) {
                    return groupControls[i].getModel().children[j];
                }
            }
        }

        /** No group control was selected, this should not happen unless group controls are not selectable to begin with. */
        throw "Unexpected state: No group control was selected.";
    }

    this.handleSelection = function (groupControl) {
        /** Must select a new group control. */
        if (!groupControl.getModel().selected) {
            /** Unselect the group control that was selected prior to this one. */
            selectedControl = this.getSelectedGroupControl();

            selectedControl.unselectionEvent();

            groupControl.setSelected(true);

            this.observable.notify(new Event(groupControl, 'GroupSelected'));
        }
    }
}