function MonitorController() {
    this.observer = new Observer(function (event) {
        ;
        }
    )

    this.observable = new Observable();
    this.observable.addObserver(this.observer);

    this.registerEvents = function () {
        ;
    }
}