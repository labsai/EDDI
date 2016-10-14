function UnknownExtensionModel(extension) {
    this.id = extension.id;
    this.idPrefix = "unknownextension_";
    this.CSSClassBase = "unknownextension_control";
    this.type = extension.type;
    this.extension = extension;

    this.additionalClasses = [];

    this.addClass = function(className) {
        if(this.additionalClasses.indexOf() == -1) {
            this.additionalClasses.push(className);
        }
    }

    this.removeClass = function(className) {
        try {
            this.additionalClasses.removeElement(className);
        } catch(ex) {
            if(ex instanceof InconsistentStateDetectedException) {
                console.log(ex.message);
            } else {
                throw ex;
            }
        }
    }
}