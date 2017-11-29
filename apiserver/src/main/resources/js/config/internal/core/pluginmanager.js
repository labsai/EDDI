/**
 * Handles plugin extension's of the client.
 *
 * @author Patrick Schwab
 * @date 07/20/2012
 * @constructor
 */
function PluginManager() {
    /**
     * Plugins are added from the configuration file ( @see configuration.js ) in __applicationStarted()__ ( @see application.js ).
     *
     * @type {Object}
     */
    this.plugins = new Object();
}