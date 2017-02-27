var eddi = eddi || {};

eddi.trackEvent = function (category, action, label) {
    ga('send', 'event', category, action, label);
};