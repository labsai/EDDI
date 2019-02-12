import * as React from 'react';
import * as renderIf from 'render-if';
import styles from './Plugin.styles';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IPackage, IPlugin, IPluginExtensions } from '../utils/AxiosFunctions';
import { connect } from 'react-redux';
import * as _ from 'lodash';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { pluginSelector } from '../../selectors/PluginSelectors';
import './Plugin.scss';
import PluginHelper from '../utils/helpers/PluginHelper';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';

interface IPublicProps {
  pluginResource: string;
  pluginType: IPluginExtensions;
  packagePayload: IPackage;
}

interface IPrivateProps extends IPublicProps {
  plugin: IPlugin;
  isLoading: boolean;
  error: Error;
}

class Plugin extends React.Component<IPrivateProps> {
  constructor(props) {
    super(props);
  }
  componentDidMount() {
    if (this.props.pluginResource) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  openViewJsonModal = () => {
    if (!_.isEmpty(this.props.pluginResource)) {
      ModalActionDispatchers.showViewJsonModal(this.props.pluginResource);
    }
  };

  isUpdateAvailable(props) {
    if (
      props.packagePayload.version === props.packagePayload.currentVersion &&
      (props.plugin && props.plugin.version < props.plugin.currentVersion) &&
      (!props.packagePayload.updatablePlugins ||
        props.packagePayload.updatablePlugins.includes(props.plugin.resource))
    ) {
      return true;
    }
    return false;
  }

  render() {
    return (
      <div>
        {renderIf(this.props.isLoading)(() => <p>{'Loading'}</p>)}
        {renderIf(!this.props.isLoading)(() => (
          <div>
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load plugin'}</p>
            ))}
            {renderIf(!this.props.error && !_.isEmpty(this.props.pluginType))(
              () => (
                <div>
                  <div style={styles.pluginContainer}>
                    <button
                      onClick={this.openViewJsonModal}
                      className={`pluginBox`}>
                      <div className={`pluginNameAndVersion`}>
                        <div className={`pluginName`}>
                          {PluginHelper.getName(
                            this.props.pluginType.type,
                            this.props.plugin,
                            true,
                          )}
                        </div>
                        <div className={`pluginVersion `}>
                          {PluginHelper.getVersion(
                            this.props.pluginType.type,
                            this.props.plugin,
                            true,
                          )}
                        </div>
                      </div>
                      <div className={`pluginDate`}>
                        {this.props.pluginType.type}
                      </div>
                      <div className={`pluginDate`}>
                        {PluginHelper.getLastModified(
                          this.props.pluginType.type,
                          this.props.plugin,
                          true,
                          <br />,
                        )}
                      </div>
                    </button>
                  </div>
                </div>
              ),
            )}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedPlugin: Component<IPublicProps, IPrivateProps> = compose<
  IPublicProps
>(pure, connect(pluginSelector), setDisplayName('Plugin'))(Plugin);

export default ComposedPlugin;
