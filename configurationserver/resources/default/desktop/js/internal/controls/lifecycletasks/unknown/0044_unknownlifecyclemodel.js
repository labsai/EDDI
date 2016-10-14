function UnknownLifecycleModel(lifecycle) {
    console.log(lifecycle)
    this.id = application.dataProvider.getNextIdGlobal();
    this.idPrefix = 'unkownlifecycle_';
    this.text = lifecycle.type;
}