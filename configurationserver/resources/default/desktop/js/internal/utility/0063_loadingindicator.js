function LoadingIndicator(model) {
    this.createRepresentation = function () {
        var representation = '<div id="' + application.configuration.loadingIndicatorPrefix + model.id + '" class="loading_indicator"></div>';

        return representation;
    }
}

function LoadingIndicatorModel(id) {
    this.id = id;
}

$.fn.showLoadingIndicator = function () {
    var model = new LoadingIndicatorModel($(this).attr('id'));
    var loadingIndicator = new LoadingIndicator(model);

    var loadingId = '#' + application.configuration.loadingIndicatorPrefix + model.id;

    $(this).before(loadingIndicator.createRepresentation());
    $(loadingId).outerHeight($(this).outerHeight());
    $(loadingId).addClass($(this).attr('class'));

    $(this).stop().fadeOut('fast', function () {
        $(loadingId).fadeIn();
    });
}

$.fn.hideLoadingIndicator = function () {
    var loadingIndicatorRefId = '#' + application.configuration.loadingIndicatorPrefix + this.attr('id');

    var instance = this;
    $(loadingIndicatorRefId).stop().fadeOut('fast', function () {
        $(loadingIndicatorRefId).remove();
        $(instance).fadeIn('slow');
    });
}