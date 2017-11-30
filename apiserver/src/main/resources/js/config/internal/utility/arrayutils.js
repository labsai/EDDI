Array.prototype.removeElementAtIndex = function (index) {
    this.splice(index, 1);
};

Array.prototype.removeElement = function (element) {
    let index = this.indexOf(element);

    if (index !== -1) {
        this.splice(index, 1);
    } else {
        console.log('No element ' + element + '. In array: ' + this);
        throw new InconsistentStateDetectedException('Inconsistency Error: Could not find a child element to be deleted in the array: ' + JSON.stringify(this));
    }
};

Array.prototype.arrayWithRange = function (min, max) {
    /** Empty array first. */
    this.splice(0, this.length);

    if (min === max) {
        this.push(max);
    } else {
        for (let i = min; i <= max; ++i) {
            this.push(i);
        }
    }

    return this;
};

Array.prototype.last = function () {
    return this[this.length - 1];
};

$.fn.exists = function () {
    return this.length !== 0;
};

function toArray(val) {
    return Array.prototype.slice.call(val);
}

Function.prototype.curry = function () {
    if (arguments.length < 1) {
        return this; //nothing to curry with - return function
    }
    let func = this;
    let args = toArray(arguments);
    let retVal = function () {
        return func.apply(this, args.concat(toArray(arguments)));
    };

    console.log(retVal);

    return retVal;
};