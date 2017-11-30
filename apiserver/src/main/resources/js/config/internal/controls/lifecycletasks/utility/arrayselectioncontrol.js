function ArraySelectionControl(model) {
    let instance = this;
    let dropdownPostfix = '_dropdown';
    this.createRepresentation = function () {
        return '<div id="' + model.idPrefix + model.id + '" class="' + model.CSSClassBase + '">'
            + '<div id="' + model.idPrefix + model.id + dropdownPostfix + '" class="' + model.CSSClassBase + dropdownPostfix + '"></div>'
            + '<div class="clear"></div>' +
            '</div>';
    };

    this.registerButtonEvents = function () {
        $('#' + model.idPrefix + model.id + dropdownPostfix).dropdown({
            value: model.backingArray[model.selectedItemIndex],
            possibleValues: model.backingArray,
            valueChanged: function (value, oldValue) {
                /** Keep model up-to-date. */
                model.selectedItemIndex = model.backingArray.indexOf(value);
            }
        });
    };

    this.getModel = function () {
        return model;
    }
}

function ArraySelectionControlModel(id, idPrefix, CSSClassBase, backingArray, selectedItemIndex) {
    this.id = id;
    this.idPrefix = idPrefix;
    this.CSSClassBase = CSSClassBase;
    this.backingArray = backingArray;
    this.selectedItemIndex = selectedItemIndex;
}