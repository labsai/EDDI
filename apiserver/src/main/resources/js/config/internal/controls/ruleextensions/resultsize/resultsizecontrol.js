function ResultSizeControl(model) {
    let sliderPostfix = '_slider';
    let referencePostfix = '_reference';
    let footerCSSClassPostfix = '_footer';

    this.resultSizeSynchronisationTimer = null;

    let instance = this;

    this.observable = new Observable();
    this.observer = new Observer(function (event) {
        switch (event.command) {
            case 'FooterClicked':
                instance.observable.notify(new Event(instance, 'DeleteExtension'));
                break;
        }
    });

    let getMinMaxFromData = function (min, max, equal) {
        if (equal != -1) {
            return {min: equal, max: equal};
        } else {
            if (max == -1) {
                return {min: min, max: application.configuration.sliderMaxResultSize + 1};
            } else if (min == -1) {
                return {min: -1, max: max};
            } else {
                return {min: min, max: max};
            }
        }
    }

    let makeRangeString = function (min, max) {
        let prefix = window.lang.convert('RESULT_SIZE_RANGE_LABEL');

        if (min == -1) {
            return prefix + window.lang.convert('SLIDER_LESS_THAN') + ' ' + max;
        }
        if (max == application.configuration.sliderMaxResultSize + 1) {
            return prefix + window.lang.convert('SLIDER_GREATER_THAN') + ' ' + min;
        } else if (min == max) {
            return prefix + min;
        } else {
            return prefix + min + ' - ' + max;
        }
    }

    for (let i = 0; i < model.footerControls.length; ++i) {
        model.footerControls[i].observable.addObserver(this.observer);
    }

    this.createRepresentation = function () {
        let tmp = getMinMaxFromData(model.min, model.max, model.equal);
        let min = tmp.min;
        let max = tmp.max;

        let representation = '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">\
            <div id="' + model.idPrefix + model.id + referencePostfix + '" class="' + model.CSSClassBase + referencePostfix + '">'
            + makeRangeString(min, max) + '</div>'
            + '<div id="' + model.idPrefix + model.id + sliderPostfix + '"></div>';

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

    let makeValuesFromUIEvent = function (ui) {
        let min = ui.values[0];
        let max = ui.values[1];

        if (max == application.configuration.sliderMaxResultSize + 1) {
            max = -1;
        }

        let equal;
        if (min == max) {
            equal = min;
        } else {
            equal = -1;
        }

        return {min: min, max: max, equal: equal};
    }

    this.registerButtonEvents = function () {
        let tmp = getMinMaxFromData(model.min, model.max, model.equal);

        $('#' + model.idPrefix + model.id + sliderPostfix).slider({
            range: true,
            min: -1,
            max: application.configuration.sliderMaxResultSize + 1,
            values: [tmp.min, tmp.max],
            start: function (event, ui) {
                if (instance.resultSizeSynchronisationTimer !== null) {
                    clearTimeout(instance.resultSizeSynchronisationTimer);
                }
            },
            slide: function (event, ui) {
                if (ui.values[0] == ui.values[1] &&
                    (ui.values[0] == application.configuration.sliderMaxResultSize + 1 || ui.values[0] == -1)
                ) {
                    /** Don't allow both handles on the far edges ('more/less than' - indicators) */
                    return false;
                } else if (ui.values[0] == -1 && ui.values[1] == application.configuration.sliderMaxResultSize + 1) {
                    /** Don't allow a handle on the far left and a handle on the far right. */
                    return false;
                } else {
                    $('#' + model.idPrefix + model.id + referencePostfix).text(makeRangeString(ui.values[0], ui.values[1]));
                }
            },
            stop: function (event, ui) {
                let valuesObj = makeValuesFromUIEvent(ui);
                let dataSyncCallback = function () {
                    let editableEvent = new Event(instance, 'ValueChanged');
                    editableEvent.value = [valuesObj.min, valuesObj.max, valuesObj.equal];
                    editableEvent.oldValue = [instance.getModel().min, instance.getModel().max, instance.getModel().equal];
                    editableEvent.editable = $(this);
                    editableEvent.isUserInput = false;
                    editableEvent.mappingPropertyJSON = ['values.min', 'values.max', 'values.equal'];
                    editableEvent.mappingPropertyControl = ['min', 'max', 'equal'];

                    instance.observable.notify(editableEvent);

                    instance.resultSizeSynchronisationTimer = null;
                };

                instance.resultSizeSynchronisationTimer = setTimeout(dataSyncCallback, application.configuration.sliderSynchronisationDelayMilis);
            }
        }).disableSelection();

        for (let i = 0; i < model.footerControls.length; ++i) {
            model.footerControls[i].registerButtonEvents();
        }

        /** Preserve additional state classes. */
        for (let i = 0; i < model.additionalClasses.length; ++i) {
            $('#' + model.idPrefix + model.id).addClass(model.additionalClasses[i]);
        }
    }
}