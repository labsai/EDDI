function UnknownLifecycleControl(model) {
    let groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';

    let groupControlBuilder = new GroupControlBuilder();
    let groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        model.text, true, true, false, 'packagecontrol', false);

    let instance = this;
    /** Configure the BehaviorLifecycleControl to act as a transparent proxy for its backing groupControl. */
    this.observer = new Observer(function (event) {
        let newEvent = jQuery.extend(true, {}, event);
        newEvent.sender = instance;
        instance.observable.notify(newEvent);
    });
    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

    this.createRepresentation = function () {
        return '<div id="' + model.idPrefix + model.id + '">' + groupControl.createRepresentation() + '<div class="clear"></div></div>';
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