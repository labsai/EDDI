/**
 * Dynamic creation of Group Controls from generic data.
 *
 * @author Patrick Schwab
 */

/**
 * The GroupControl object handles creation and action handling of a generic group control generated from a specified GroupControlModel.
 * It is a composite object.
 *
 * @param groupControlModelCtr The group control model that defines this group control.
 * @param groupCSSClassBaseCtr The group controls' base CSS class, all CSS classes are relative to this base.
 *                             NOTE: The following CSS classes define the full control:
 *                                   __groupCSSClassBase__
 *                                   __groupCSSClassBase__ + '_opened'
 *                                   __groupCSSClassBase__ + '_closed'
 *                                   __groupCSSClassBase__ + '_leftbutton' + '_opened'
 *                                   __groupCSSClassBase__ + '_leftbutton' + '_closed'
 *                                   __groupCSSClassBase__ + '_rightbutton'
 *                                   __groupCSSClassBase__ + '_text' + '_opened'
 *                                   __groupCSSClassBase__ + '_text' + '_closed'
 *
 * @constructor
 */
function GroupControl(groupControlModel, groupCSSClassBase) {
    var leftButtonIdPrefix = "lbut_";
    var rightButtonIdPrefix = "rbut_";
    var openCSSClassPostfix = "_opened";
    var closeCSSClassPostfix = "_closed";
    var leftButtonCSSClassBase = "_leftbutton";
    var rightButtonCSSClass = "_rightbutton";
    var sequenceNumberCSSClass = "_sequencenumber";
    var sequenceNumberTextCSSClass = "_sequencenumbertext";
    var subheaderCSSClassPostfix = "_subheader";
    var footerCSSClassPostfix = "_footer";
    var textCSSBaseClass = "_text";
    var actionBoxPostfix = '_actionbox';
    var actionRowPostfix = '_actionrow';
    var actionDescriptorPostfix = '_actiondescriptor';
    var actionPostfix = '_action';
    var isSelectable = groupControlModel.selected != null;
    var isEditable = typeof groupControlModel.editable === 'undefined' ? true : groupControlModel.editable;

    var instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteGroup'));
                break;
        }
    });

    for (var i = 0; i < groupControlModel.footerControls.length; ++i) {
        groupControlModel.footerControls[i].observable.addObserver(this.observer);
    }

    this.createRepresentation = function () {
        var classPostfix = groupControlModel.open == true ? openCSSClassPostfix : closeCSSClassPostfix;

        var representation =
            '<div id="' + groupControlModel.idPrefix + groupControlModel.id + '" class="' + groupCSSClassBase + classPostfix + '">\
                <div class="' + groupCSSClassBase + '_header' + '">\
                <div id="' + leftButtonIdPrefix + groupControlModel.idPrefix + groupControlModel.id + '" class="' + groupCSSClassBase + leftButtonCSSClassBase + classPostfix + '"></div> \
                <span class="' + groupCSSClassBase + textCSSBaseClass + classPostfix + '">' + groupControlModel.text + '</span>';

        if (groupControlModel.open && (typeof groupControlModel.rightButtonShown === 'undefined' || groupControlModel.rightButtonShown)) {
            var rightButtonRepresentation = '<div id="' + rightButtonIdPrefix + groupControlModel.idPrefix + groupControlModel.id + '" class="' + groupCSSClassBase + rightButtonCSSClass + '"></div>';

            representation += rightButtonRepresentation;
        }

        if (groupControlModel.open && groupControlModel.headerControls && groupControlModel.headerControls.length > 0) {
            representation += '<div class="' + groupCSSClassBase + subheaderCSSClassPostfix + '">';

            for (var i = 0; i < groupControlModel.headerControls.length; ++i) {
                representation += groupControlModel.headerControls[i].createRepresentation();
            }

            representation += '<div class="clear"></div>';
            representation += '</div>'
        }

        if (groupControlModel.sequenceNumber) {
            var sequenceNumberRepresentation = '<div class="' + groupCSSClassBase + sequenceNumberCSSClass + '">' +
                '<div class="' + groupCSSClassBase + sequenceNumberTextCSSClass + '">' + groupControlModel.sequenceNumber + '</div></div>';

            representation += sequenceNumberRepresentation;
        }

        representation += '<div class="clear"></div>';
        representation += '</div>';

        if(groupControlModel.open && groupControlModel.actions) {
            representation += '<div id="' + groupControlModel.idPrefix + groupControlModel.id + actionBoxPostfix +
                '" class="' + groupCSSClassBase + actionBoxPostfix +'">\
                <div class="' + groupCSSClassBase + actionRowPostfix + '">\
                <div class="' + groupCSSClassBase + actionDescriptorPostfix + '">' + window.lang.convert('ACTIONS') + '</div>\
                <div id="' + groupControlModel.idPrefix + groupControlModel.id + actionPostfix +
                '" class="' + groupCSSClassBase + actionPostfix + '">' + groupControlModel.actions.join(', ') + '</div>\
                <div class="clear"></div>\
                </div></div>';
        }

        /** Show the children. */
        if (groupControlModel.open) {
            var sortableId = application.configuration.referencePrefix + groupControlModel.idPrefix + groupControlModel.id;
            representation += '<div id="' + sortableId + '" class="' + groupCSSClassBase + '_sortable_inner">';

            for (var i = 0; i < groupControlModel.children.length; ++i) {
                representation += groupControlModel.children[i].createRepresentation();
            }

            representation += '</div>';
        }

        /** Show the footer controls. */
        if (groupControlModel.footerControls && groupControlModel.footerControls.length > 0) {
            representation += '<div class="' + groupCSSClassBase + footerCSSClassPostfix + '">';
            for (var i = 0; i < groupControlModel.footerControls.length; ++i) {
                if (groupControlModel.footerControls[i].getModel().showOpenOnly && !groupControlModel.open) {
                    break;
                }

                if (groupControlModel.deleteable === false) {
                    break;
                }

                representation += groupControlModel.footerControls[i].createRepresentation();
            }

            representation += '<div class="clear"></div>';
            representation += '</div>';
        }

        representation += '</div>';

        return representation;
    }

    this.getWidth = function () {
        return $('#' + groupControlModel.idPrefix + groupControlModel.id).outerWidth(true);
    }

    this.registerButtonEvents = function () {
        var instance = this;

        /** Preserve additional state classes. */
        for (var i = 0; i < groupControlModel.additionalClasses.length; ++i) {
            $('#' + groupControlModel.idPrefix + groupControlModel.id).addClass(groupControlModel.additionalClasses[i]);
        }

        $('#' + leftButtonIdPrefix + groupControlModel.idPrefix + groupControlModel.id).click(function () {
            instance.handleOpenCloseEvent();

            instance.observable.notify(new Event(instance, 'SizeChanged'));

            return false;
        });

        $('#' + rightButtonIdPrefix + groupControlModel.idPrefix + groupControlModel.id).click(function () {
            instance.observable.notify(new Event(instance, 'RightButtonClicked'));

            return false;
        });

        if (isSelectable) {
            $('#' + groupControlModel.idPrefix + groupControlModel.id).click(function (event) {
                instance.observable.notify(new Event(instance, 'GroupSelected'));
            });

            this.setSelected(groupControlModel.selected);
        }

        if (isEditable) {
            var selector;
            var commandString;
            var widthString;
            var mappingProperty;
            if (groupCSSClassBase === 'groupcontrol') {
                selector = $('#' + groupControlModel.idPrefix + groupControlModel.id + ' .groupcontrol_text_opened');
                commandString = 'GroupLabelEdited';
                widthString = '200px';
                mappingProperty = 'groupName';
            } else if (groupCSSClassBase === 'packagecontrol') {
                selector = $('#' + groupControlModel.idPrefix + groupControlModel.id + ' div .packagecontrol_text_opened, '
                    + '#' + groupControlModel.idPrefix + groupControlModel.id + ' div .packagecontrol_text_closed');
                commandString = 'PackageLabelEdited';
                widthString = '140px';
                mappingProperty = 'name';
            }

            selector.editable(function (value, settings) {
                var htmlIdResolver = new HTMLIDResolver();
                var packageId = $(this).parent().parent().attr('id');
                var sender = htmlIdResolver.resolveId(packageId);

                var editableEvent = new Event(sender, commandString);
                editableEvent.value = value;
                editableEvent.settings = settings;
                editableEvent.oldValue = sender.getModel().text;
                editableEvent.editable = $(this);
                editableEvent.isUserInput = true;
                editableEvent.mappingPropertyJSON = mappingProperty;
                editableEvent.mappingPropertyControl = 'text';

                instance.observable.notify(editableEvent);

                return application.bindingManager.bindFromString(value);
            }, {
                type:'text',
                style:'inherit',
                width:widthString,
                submit:window.lang.convert('EDITABLE_OK'),
                cancel:window.lang.convert('EDITABLE_CANCEL'),
                placeholder:window.lang.convert('EDITABLE_PLACEHOLDER'),
                data:function (value, settings) {
                    /** Unescape innerHtml before editing. */
                    return application.bindingManager.bindToString(value);
                }
            });
        }

        function split(val) {
            return val.split(/,\s*/);
        }

        function extractLast(term) {
            return split(term).pop();
        }

        $('#' + groupControlModel.idPrefix + groupControlModel.id + actionPostfix).editable(function (value, settings) {
            var editableEvent = new Event(instance, 'ValueChanged');
            editableEvent.value = value;
            editableEvent.settings = settings;
            editableEvent.oldValue = instance.getModel().actions;
            editableEvent.editable = $(this);
            editableEvent.isUserInput = true;
            editableEvent.mappingPropertyJSON = 'actions';
            editableEvent.mappingPropertyControl = 'actions';

            instance.observable.notify(editableEvent);

            return application.bindingManager.bindFromString(value);
        }, {
            type:'autocomplete',
            style:'inherit',
            onblur:'submit',
            placeholder: window.lang.convert('EDITABLE_PLACEHOLDER'),
            data:function (value, settings) {
                /** Unescape innerHtml before editing. */
                return application.bindingManager.bindToString(value);
            },
            autocomplete:{
                options:{
                    source:function (request, response) {
                        var anchors = application.url.serializeAnchors();

                        var outputKeys;
                        if (anchors.hasOwnProperty(application.configuration.packageParentIdHashKey) &&
                            anchors.hasOwnProperty(application.configuration.packageParentVersionHashKey)) {
                            outputKeys = application.dataProvider.readOutputKeysPackage(
                                anchors[application.configuration.packageParentIdHashKey],
                                anchors[application.configuration.packageParentVersionHashKey],
                                request.term
                            );
                        }

                        response(outputKeys);
                    },
                    search:function () {
                        // custom minLength
                        var term = extractLast(this.value);
                        if (term.length < 1) {
                            return false;
                        }
                    },
                    focus:function () {
                        // prevent value inserted on focus
                        return false;
                    },
                    select:function (event, ui) {
                        var terms = split(this.value);
                        // remove the current input
                        terms.pop();
                        // add the selected item
                        terms.push(ui.item.value);
                        // add placeholder to get the comma-and-space at the end
                        //terms.push("");
                        this.value = terms.join(", ");
                        return false;
                    },
                    minLength:1,
                    open:function () {
                        $('.ui-autocomplete-category').next('.ui-menu-item').addClass('ui-first');
                    }
                }
            }
        });

        /** Don't forget telling the children about it as well. */
        for (var i = 0; i < groupControlModel.children.length; ++i) {
            groupControlModel.children[i].registerButtonEvents();
        }

        for (var i = 0; i < groupControlModel.headerControls.length; ++i) {
            groupControlModel.headerControls[i].registerButtonEvents();
        }

        for (var i = 0; i < groupControlModel.footerControls.length; ++i) {
            groupControlModel.footerControls[i].registerButtonEvents();
        }
    }

    this.handleOpenCloseEvent = function () {
        groupControlModel.open = !groupControlModel.open;

        $('#' + groupControlModel.idPrefix + groupControlModel.id).replaceWith(this.createRepresentation());

        /** Must re-register button events after replacing contents. */
        this.registerButtonEvents();
    }

    this.unselectionEvent = function () {
        this.setSelected(false);
    }

    this.setSelected = function (selectionState) {
        groupControlModel.selected = selectionState;

        if (groupControlModel.selected) {
            $('#' + groupControlModel.idPrefix + groupControlModel.id).css({'background-color':'blue'});
        } else {
            if (groupControlModel.open) {
                $('#' + groupControlModel.idPrefix + groupControlModel.id).css({'background-color':'orange'});
            } else {
                $('#' + groupControlModel.idPrefix + groupControlModel.id).css({'background-color':'red'});
            }
        }
    }

    this.getModel = function () {
        return groupControlModel;
    }
}

/**
 * The GroupControlModel specifies a data model that can be used to build a GroupControl instance.
 *
 * @param id The GroupControls' unique identification.
 * @param text The GroupControls' text value.
 * @param open Whether or not the GroupControl is currently opened.
 * @constructor
 */
function GroupControlModel(id, idPrefix, text, footerControls, sequenceNumber, open, selected, editable, deleteable,
                           rightButtonShown, actions) {
    this.id = id;
    this.idPrefix = idPrefix;
    this.text = text;
    this.headerControls = [];
    this.footerControls = footerControls;
    this.sequenceNumber = sequenceNumber;
    this.open = open;
    this.selected = selected;
    this.editable = editable;
    this.deleteable = deleteable;
    this.rightButtonShown = rightButtonShown;
    this.actions = actions;
    this.children = [];
    this.additionalClasses = [];

    this.addClass = function (className) {
        if (this.additionalClasses.indexOf() == -1) {
            this.additionalClasses.push(className);
        }
    }

    this.removeClass = function (className) {
        try {
            this.additionalClasses.removeElement(className);
        } catch (ex) {
            if (ex instanceof InconsistentStateDetectedException) {
                console.log(ex.message);
            } else {
                throw ex;
            }
        }
    }

    /**
     * @param child element to be added to the composition, must also implement __createRepresentation()__
     */
    this.addChild = function (child) {
        this.children.push(child);
    }

    this.removeChild = function (child) {
        this.children.removeElement(child);
    }
}