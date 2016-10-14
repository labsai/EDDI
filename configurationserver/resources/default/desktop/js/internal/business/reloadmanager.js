function ReloadManager() {
    var hasChanges = false;

    var warnChanges = function(){
        return window.lang.convert('WARN_UNSAVED_CHANGES');
    }

    this.performWithoutConfirmation = function(method) {
        $(window).unbind('beforeunload');
        method();
        $(window).bind('beforeunload');
    }

    this.changesHappened = function() {
        console.log('changes happened');

        if(hasChanges == false) {
            hasChanges = true;
            $(window).bind('beforeunload', warnChanges);
        }
    }

    this.hasChanges = function() {
        return hasChanges;
    }
}