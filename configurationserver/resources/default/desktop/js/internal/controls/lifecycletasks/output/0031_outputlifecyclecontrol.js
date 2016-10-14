function OutputLifecycleControl(model) {
    var groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';
    var selectionControlIdPrefix = model.idPrefix + 'selectioncontrol_';
    var selectionControlCSSClassBase = 'selectioncontrol';

    var groupControlBuilder = new GroupControlBuilder();
    var groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        window.lang.convert('OUTPUTLIFECYCLE_NAME'), true, true, false, 'packagecontrol', false);

    var instance = this;
    /** Configure the OutputLifecycleControl to act as a transparent proxy for its backing groupControl. */
    this.observer = new Observer(function (event) {
        var newEvent = jQuery.extend(true, {}, event);
        newEvent.sender = instance;
        instance.observable.notify(newEvent);
    });
    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

    var nameArray = [];

    for (var i = 0; i < model.backingArray.length; ++i) {
        nameArray.push(model.backingArray[i].name);
    }

    var selectionModel = new ArraySelectionControlModel(model.id, selectionControlIdPrefix,
        selectionControlCSSClassBase, nameArray,
        model.selectedItemIndex);

    var selectionControl = new ArraySelectionControl(selectionModel);
    groupControl.getModel().addChild(selectionControl);
    model.children.push(groupControl);

    this.createRepresentation = function () {
        var representation = '<div id="' + model.idPrefix + model.id + '">' + groupControl.createRepresentation() + '<div class="clear"></div></div>';

        return representation;
    }

    this.getModel = function () {
        return model;
    }

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    }

    this.registerButtonEvents = function () {
        groupControl.registerButtonEvents();
    }
}