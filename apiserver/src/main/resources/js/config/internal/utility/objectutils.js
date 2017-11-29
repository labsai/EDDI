function ObjectUtils() {
    ;
}

ObjectUtils.prototype.getNumberOfProperties = function (object) {
    var count = 0;

    for (var key in object) {
        if (object.hasOwnProperty(key)) {
            count++;
        }
    }

    return count;
}