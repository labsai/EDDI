function ObjectUtils() {
    ;
}

ObjectUtils.prototype.getNumberOfProperties = function (object) {
    let count = 0;

    for (let key in object) {
        if (object.hasOwnProperty(key)) {
            count++;
        }
    }

    return count;
};