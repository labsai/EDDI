function OutputReferenceControl(model) {
    var filterRowPostfix = '_filterrow';
    var filterDescriptorPostfix = '_filterdescriptor';
    var filterPostfix = '_filter';
    var inputValuePostfix = '_inputvalue';
    var footerCSSClassPostfix = '_footer';

    var instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteExtension'));
                break;
        }
    });

    for (var i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    this.createRepresentation = function () {
        var representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">\
            <div id="' + model.idPrefix + model.id + inputValuePostfix + '" class="' + model.CSSClassBase + inputValuePostfix + '"></div><div class="clear"></div>\
            <div class="' + model.CSSClassBase + filterRowPostfix + '">\
            <div class="' + model.CSSClassBase + filterDescriptorPostfix + '">' + window.lang.convert('OUTPUTREFERENCE_FILTER') + '</div>\
            <div id="' + model.idPrefix + model.id + filterPostfix + '" class="' + model.CSSClassBase + filterPostfix + '">' + model.filter + '</div>\
            <div class="clear"></div>\
            </div>';

        representation += '<div class="' + model.CSSClassBase + footerCSSClassPostfix + '">';

        for (var i = 0; i < model.footerControls.length; ++i) {
            representation += model.footerControls[i].createRepresentation();
        }

        representation += '<div class="clear"></div></div></div>';

        return representation;
    }

    this.getModel = function () {
        return model;
    }

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    }

    this.registerButtonEvents = function () {
        var that = this;
        $('#' + model.idPrefix + model.id + inputValuePostfix).dropdown({
            value: model.inputValue,
            possibleValues: application.dataProvider.getPossibleValuesForOutputReferenceInputValue(),
            valueChanged: function (value, oldValue) {
                var editableEvent = new Event(that, 'ValueChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.inputValue';
                editableEvent.mappingPropertyControl = 'inputValue';

                that.observable.notify(editableEvent);
            }
        });

        $('#' + model.idPrefix + model.id + filterPostfix).editable(function (value, settings) {
            var editableEvent = new Event(instance, 'ValueChanged');
            editableEvent.value = value;
            editableEvent.settings = settings;
            editableEvent.oldValue = instance.getModel().filter;
            editableEvent.editable = $(this);
            editableEvent.isUserInput = true;
            editableEvent.mappingPropertyJSON = 'values.filter';
            editableEvent.mappingPropertyControl = 'filter';

            instance.observable.notify(editableEvent);

            return application.bindingManager.bindFromString(value);
        }, {
            type: 'text',
            style: 'inherit',
            submit: window.lang.convert('EDITABLE_OK'),
            cancel: window.lang.convert('EDITABLE_CANCEL'),
            placeholder: window.lang.convert('EDITABLE_PLACEHOLDER'),
            data: function (value, settings) {
                /** Unescape innerHtml before editing. */
                return application.bindingManager.bindToString(value);
            }
        });

        for (var i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for (var i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}