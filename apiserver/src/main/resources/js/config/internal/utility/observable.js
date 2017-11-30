function Observable() {
    let observers = [];

    this.notify = function (event) {
        for (let i = 0; i < observers.length; ++i) {
            observers[i].eventReceived(event);
        }
    };

    this.addObserver = function (listener) {
        let index = observers.indexOf(listener);

        /** Only register each listener once. */
        if (index === -1) {
            observers.push(listener);
        }
    };

    this.removeObserver = function (listener) {
        let index = observers.indexOf(listener);

        if (index !== -1) {
            observers.splice(index, 1);
        } else {
            console.log('Tried to remove an observer that was not observing.');
        }
    };

    this.getObservers = function () {
        return observers;
    }
}

function Observer(eventReceived) {
    this.eventReceived = eventReceived;
    /** void eventReceived(Event event) */
}

function Event(sender, command) {
    this.sender = sender;
    this.command = command;
}