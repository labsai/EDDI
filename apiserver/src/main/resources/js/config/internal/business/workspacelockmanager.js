function WorkspaceLockManager() {
    let isLocked = true;

    this.lock = function () {
        isLocked = true;
    };

    this.unlock = function () {
        isLocked = false;
    };

    this.isLocked = function () {
        return isLocked;
    }
}