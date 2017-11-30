/**
 * Creates unique identifiers in the scope in which it is first constructed.
 * @constructor
 */
function UIDGenerator() {
    let uid = 0;

    this.next = function () {
        return uid++;
    }
}