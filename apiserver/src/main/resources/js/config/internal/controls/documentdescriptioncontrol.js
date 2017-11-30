function DocumentDescriptionControl(model, baseCSSClass) {
    let leftButtonIdPrefix = "lbut_";
    let openCSSClassPostfix = "_opened";
    let closeCSSClassPostfix = "_closed";
    let leftButtonCSSClassBase = "_leftbutton";
    let sequenceNumberCSSClass = "_sequencenumber";
    let sequenceNumberTextCSSClass = "_sequencenumbertext";
    let headerCSSClassPostfix = "_header";
    let subheaderCSSClassPostfix = "_subheader";
    let contentCSSClassPostfix = "_content";
    let footerCSSClassPostfix = "_footer";
    let titleCSSBaseClass = "_title";
    let descriptionCSSBaseClass = "_description";

    let instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteDocumentDescription'));
                break;
        }
    });

    this.getModel = function () {
        return model;
    };

    DocumentDescriptionControl.getRootElementSelector = function () {
        return $('.' + baseCSSClass + openCSSClassPostfix + ', .' + baseCSSClass + closeCSSClassPostfix);
    };

    for (let i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    this.createRepresentation = function () {
        let classPostfix = model.open === true ? openCSSClassPostfix : closeCSSClassPostfix;

        let representation =
            '<div id="' + model.idPrefix + model.id + '" class="' + baseCSSClass + classPostfix + '">\
                <div class="' + baseCSSClass + headerCSSClassPostfix + '">\
                <a href="#"><div id="' + leftButtonIdPrefix + model.idPrefix + model.id + '" class="' + baseCSSClass + leftButtonCSSClassBase + classPostfix + '"></div></a> \
                <span class="' + baseCSSClass + titleCSSBaseClass + classPostfix + '">' + model.title + '</span>';

//        if (model.sequenceNumber !== false) {
//            let sequenceNumberRepresentation = '<div class="' + baseCSSClass + sequenceNumberCSSClass + '">' +
//                '<div class="' + baseCSSClass + sequenceNumberTextCSSClass + '">' + model.sequenceNumber + '</div></div>';
//
//            representation += sequenceNumberRepresentation;
//        }

        /** Show the header controls. */
        if (model.headerControls && model.headerControls.length > 0) {
            representation += '<div class="' + baseCSSClass + subheaderCSSClassPostfix + '">';

            for (let i = 0; i < model.headerControls.length; ++i) {
                representation += model.headerControls[i].createRepresentation();
            }

            representation += '<div class="clear"></div>';
            representation += '</div>'
        }

        representation += '<div class="clear"></div>';
        representation += '</div>';

        /** Show the description. */
        if (model.open) {
            representation += '<div class="' + baseCSSClass + contentCSSClassPostfix + '">';
            representation += '<div class="' + baseCSSClass + descriptionCSSBaseClass + '">' + model.description + '</div>';

            representation += '<div class="clear"></div>';
            representation += '</div>';
        }

        /** Show the footer controls. */
        if (model.footerControls && model.footerControls.length > 0) {
            representation += '<div class="' + baseCSSClass + footerCSSClassPostfix + '">';
            for (let i = 0; i < model.footerControls.length; ++i) {
                if (model.footerControls[i].getModel().showOpenOnly && !model.open) {
                    break;
                }

//                if (model.editable === false) {
//                    break;
//                }

                representation += model.footerControls[i].createRepresentation();
            }

            representation += '<div class="clear"></div>';
            representation += '</div>';
        }

        representation += '</div>';

        return representation;
    };

    this.getWidth = function () {
        return $('#' + model.idPrefix + model.id).outerWidth(true);
    };

    this.registerButtonEvents = function () {
        let instance = this;

        /** Preserve additional state classes. */
        for (let i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }

        $('#' + leftButtonIdPrefix + model.idPrefix + model.id).click(function () {
            instance.handleOpenCloseEvent();

            instance.observable.notify(new Event(instance, 'SizeChanged'));

            return false;
        });

        if (model.editable) {
            let selector = $('#' + model.idPrefix + model.id + ' div .' + baseCSSClass + titleCSSBaseClass + openCSSClassPostfix + ', '
                + '#' + model.idPrefix + model.id + ' div .' + baseCSSClass + titleCSSBaseClass + closeCSSClassPostfix);

            selector.editable(function (value, settings) {
                let htmlIdResolver = new HTMLIDResolver();
                let packageId = $(this).parent().parent().attr('id');
                let sender = htmlIdResolver.resolveId(packageId);

                let editableEvent = new Event(sender, 'DocumentDescriptionLabelEdited');
                editableEvent.value = value;
                editableEvent.settings = settings;
                editableEvent.oldValue = sender.getModel().text;
                editableEvent.editable = $(this);
                editableEvent.isUserInput = true;
                editableEvent.mappingPropertyJSON = 'documentDescription';
                editableEvent.mappingPropertyControl = 'text';

                instance.observable.notify(editableEvent);

                return application.bindingManager.bindFromString(value);
            }, {
                type: 'text',
                style: 'inherit',
                width: '140px',
                submit: window.lang.convert('EDITABLE_OK'),
                cancel: window.lang.convert('EDITABLE_CANCEL'),
                placeholder: window.lang.convert('EDITABLE_PLACEHOLDER'),
                data: function (value, settings) {
                    /** Unescape innerHtml before editing. */
                    return application.bindingManager.bindToString(value);
                }
            });
        }

        for (let i = 0; i < model.headerControls.length; ++i) {
            model.headerControls[i].registerButtonEvents();
        }

        for (let i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }
    };

    this.handleOpenCloseEvent = function () {
        model.open = !model.open;

        $('#' + model.idPrefix + model.id).replaceWith(this.createRepresentation());

        /** Must re-register button events after replacing contents. */
        this.registerButtonEvents();
    }
}

/**
 * The DocumentDescriptionControlModel specifies a data model that can be used to build a DocumentDescriptionControl instance.
 *
 * @param id The DocumentDescriptionControls' unique identification.
 * @param title The DocumentDescriptionControl' title value.
 * @constructor
 */
function DocumentDescriptionControlModel(id, idPrefix, title, description, headerControls, footerControls, sequenceNumber, open, editable) {
    this.id = id;
    this.idPrefix = idPrefix;
    this.title = title;
    this.description = description;
    this.headerControls = headerControls;
    this.footerControls = footerControls;
    this.sequenceNumber = sequenceNumber;
    this.open = open;
    this.editable = editable;

    this.additionalClasses = [];

    this.addClass = function (className) {
        if (this.additionalClasses.indexOf() === -1) {
            this.additionalClasses.push(className);
        }
    };

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
}