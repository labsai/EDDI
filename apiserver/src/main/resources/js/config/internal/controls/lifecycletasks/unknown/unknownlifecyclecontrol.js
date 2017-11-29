function UnknownLifecycleControl(model) {
    var groupControlIdPrefix = model.idPrefix + 'backinggroupcontrol_';

    var groupControlBuilder = new GroupControlBuilder();
    var groupControl = groupControlBuilder.createStandardUneditableGroupControl(model.id, groupControlIdPrefix,
        model.text, true, true, false, 'packagecontrol', false);

    var instance = this;
    /** Configure the BehaviorLifecycleControl to act as a transparent proxy for its backing groupControl. */
    this.observer = new Observer(function (event) {
        var newEvent = jQuery.extend(true, {}, event);
        newEvent.sender = instance;
        instance.observable.notify(newEvent);
    });
    this.observable = new Observable();
    groupControl.observable.addObserver(this.observer);

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