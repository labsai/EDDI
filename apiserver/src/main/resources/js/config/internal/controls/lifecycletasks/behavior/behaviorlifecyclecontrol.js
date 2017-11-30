function BehaviorLifecycleControl(model) {
    let groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';
    let selectionControlIdPrefix = model.idPrefix + 'selectioncontrol_';
    let selectionControlCSSClassBase = 'selectioncontrol';

    let groupControlBuilder = new GroupControlBuilder();
    let groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        window.lang.convert('BEHAVIORLIFECYCLE_NAME'), true, true, false, 'packagecontrol', false);

    let instance = this;
    /** Configure the BehaviorLifecycleControl to act as a transparent proxy for its backing groupControl. */
    this.observer = new Observer(function (event) {
        let newEvent = jQuery.extend(true, {}, event);
        newEvent.sender = instance;
        instance.observable.notify(newEvent);
    });
    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

    let nameArray = [];

    for (let i = 0; i < model.backingArray.length; ++i) {
        nameArray.push(model.backingArray[i].name);
    }

    let selectionModel = new ArraySelectionControlModel(model.id, selectionControlIdPrefix,
        selectionControlCSSClassBase, nameArray,
        model.selectedItemIndex);

    let selectionControl = new ArraySelectionControl(selectionModel);
    groupControl.getModel().addChild(selectionControl);
    model.children.push(groupControl);

    this.createRepresentation = function () {
        let representation = '<div id="' + model.idPrefix + model.id + '">' + groupControl.createRepresentation() + '<div class="clear"></div></div>';

        return representation;
    };

    this.getModel = function () {
        return model;
    };

    this.getHeight = function () {
        return $('#' + model.idPrefix + model.id).outerHeight(true);
    };

    this.registerButtonEvents = function () {
        groupControl.registerButtonEvents();
    }
}