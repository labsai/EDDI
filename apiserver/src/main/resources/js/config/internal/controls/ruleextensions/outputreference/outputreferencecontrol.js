function OutputReferenceControl(model) {
    let filterRowPostfix = '_filterrow';
    let filterDescriptorPostfix = '_filterdescriptor';
    let filterPostfix = '_filter';
    let inputValuePostfix = '_inputvalue';
    let footerCSSClassPostfix = '_footer';

    let instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteExtension'));
                break;
        }
    });

    for (let i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">\
            <div id="' + model.idPrefix + model.id + inputValuePostfix + '" class="' + model.CSSClassBase + inputValuePostfix + '"></div><div class="clear"></div>\
            <div class="' + model.CSSClassBase + filterRowPostfix + '">\
            <div class="' + model.CSSClassBase + filterDescriptorPostfix + '">' + window.lang.convert('OUTPUTREFERENCE_FILTER') + '</div>\
            <div id="' + model.idPrefix + model.id + filterPostfix + '" class="' + model.CSSClassBase + filterPostfix + '">' + model.filter + '</div>\
            <div class="clear"></div>\
            </div>';

        representation += '<div class="' + model.CSSClassBase + footerCSSClassPostfix + '">';

        for (let i = 0; i < model.footerControls.length; ++i) {
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
        let that = this;
        $('#' + model.idPrefix + model.id + inputValuePostfix).dropdown({
            value: model.inputValue,
            possibleValues: application.dataProvider.getPossibleValuesForOutputReferenceInputValue(),
            valueChanged: function (value, oldValue) {
                let editableEvent = new Event(that, 'ValueChanged');

                editableEvent.value = value;
                editableEvent.oldValue = oldValue;
                editableEvent.isUserInput = false;
                editableEvent.mappingPropertyJSON = 'values.inputValue';
                editableEvent.mappingPropertyControl = 'inputValue';

                that.observable.notify(editableEvent);
            }
        });

        $('#' + model.idPrefix + model.id + filterPostfix).editable(function (value, settings) {
            let editableEvent = new Event(instance, 'ValueChanged');
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

        for (let i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for (let i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}