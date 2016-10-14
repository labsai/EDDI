function log(logType, msg) {
    var img = new Image();
    img.src = "/logging?type=" +
        encodeURIComponent(logType) +
        "&msg=" + encodeURIComponent(msg);
    if (logType == 'ERROR') {
        alert('An error has occurred. Please contact the administrator.');
    }
}

// Auto-log uncaught JS errors
window.onerror = function (msg, url, line) {
    log(1, msg);
    return true;
};
