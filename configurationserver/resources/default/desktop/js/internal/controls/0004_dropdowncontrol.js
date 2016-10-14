function DropDownControl(model) {
    var dropDownControlId = '_dropdowncontrol';
    this.observable = new Observable();

    this.createRepresentation = function () {
        var representation = '<span id="' + model.id + '" class="' + model.containerCssClass + '"><label>' + model.title + ': <span id="' + model.id + dropDownControlId + '" class="' + model.dropDownCssClass + '"></span></label></span>';

        return representation;
    }

    this.registerButtonEvents = function () {
        var instance = this;

        /* Apply the dropdown handler to the table length control */
        $('#' + model.id + dropDownControlId).dropdown({
            value:model.selectedValue,
            possibleValues:model.values,
            displayInline:true,
            valueChanged:function (value, oldValue) {
                var botDropDownChangedEvent = new Event(instance, model.observableEventName);

                botDropDownChangedEvent.value = value;
                botDropDownChangedEvent.oldValue = oldValue;

                instance.observable.notify(botDropDownChangedEvent);
            }
        });
    }
}

function DropDownControlModel(id, containerCssClass, dropDownCssClass, title, firstItemIsAllItem, values, selectedValue, observableEventName) {
    this.id = id;
    this.containerCssClass = containerCssClass;
    this.dropDownCssClass = dropDownCssClass;
    this.title = title;
    this.firstItemIsAllItem = firstItemIsAllItem;
    this.values = values;
    this.selectedValue = selectedValue;
    this.observableEventName = observableEventName;
}