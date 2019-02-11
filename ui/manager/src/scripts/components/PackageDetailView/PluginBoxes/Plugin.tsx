import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IOptions } from '../PackageView';
import Parser from '../../utils/Parser';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import { IPlugin, IPluginExtensions } from '../../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import styles from './Plugin.styles';
import { connect } from 'react-redux';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import * as PluginType from '../../utils/EddiTypes';
import PluginHelper from '../../utils/helpers/PluginHelper';
import { REGULAR_DICTIONARY } from '../../utils/EddiTypes';

interface IPublicProps {
  pluginType: IOptions;
  pluginResource: string;
  editDisabled: boolean;
  deletePlugin(extensionKey: number): void;
  updatePlugin(extensionKey: number, newPlugin: IPluginExtensions): void;
}
interface IPrivateProps extends IPublicProps {
  plugin: IPlugin;
}

class Plugin extends React.Component<IPrivateProps> {
  componentDidMount() {
    if (this.props.pluginResource) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  componentWillReceiveProps(nextProps) {
    if (
      nextProps.pluginResource &&
      nextProps.pluginResource !== this.props.pluginResource
    ) {
      eddiApiActionDispatchers.fetchPluginAction(nextProps.pluginResource);
    }
  }

  deletePlugin = (extensionKey: number) => {
    this.props.deletePlugin(extensionKey);
  };

  openAddPluginsModal = () => {
    let extensionList: string[] = [];
    let pluginType;
    if (this.props.pluginType.type === PluginType.PARSER) {
      if (
        this.props.pluginType.config &&
        !_.isEmpty(this.props.pluginType.extensions.dictionaries)
      ) {
        extensionList = this.props.pluginType.extensions.dictionaries.map(p => {
          return p.config.uri;
        });
      }
      pluginType = PluginType.REGULAR_DICTIONARY;
    } else {
      pluginType = this.props.pluginType.type;
      extensionList =
        (this.props.pluginResource && [this.props.pluginResource]) || [];
    }
    ModalActionDispatchers.showAddPluginsModal(
      pluginType,
      extensionList,
      this.updatePluginResource,
    );
  };

  updatePluginResource = (newPluginResourceList: string[]) => {
    // todo: REFACTOR THIS!
    if (this.props.pluginType.type === PluginType.PARSER) {
      let otherDictionaries = [];
      if (
        !_.isEmpty(this.props.pluginType.extensions) &&
        !_.isEmpty(this.props.pluginType.extensions.dictionaries)
      ) {
        otherDictionaries = this.props.pluginType.extensions.dictionaries.filter(
          d => {
            return { ...d };
          },
        );
      }
      const newRegularDictionaryList = newPluginResourceList.map(resource => {
        return { type: REGULAR_DICTIONARY, config: { uri: resource } };
      });
      const newPlugin: IPluginExtensions = {
        ...this.props.pluginType,
        extensions: {
          dictionaries: otherDictionaries.concat(newRegularDictionaryList),
          corrections:
            !_.isEmpty(this.props.pluginType.extensions) &&
            !_.isEmpty(this.props.pluginType.extensions.corrections)
              ? this.props.pluginType.extensions.corrections
              : null,
        },
      };
      this.props.updatePlugin(this.props.pluginType.extensionKey, newPlugin);
    } else if (!_.isEmpty(newPluginResourceList)) {
      const plugin: IPluginExtensions = {
        ...this.props.pluginType,
        config: {
          ...this.props.pluginType.config,
          uri: newPluginResourceList[0],
        },
      };
      this.props.updatePlugin(this.props.pluginType.extensionKey, plugin);
    }
  };

  getNameStyling() {
    if (this.props.plugin.version === this.props.plugin.currentVersion) {
      return { ...styles.pluginName };
    } else {
      return {
        ...styles.pluginName,
        ...styles.updateAvailableTextColor,
      };
    }
  }

  getBoxStyling() {
    if (this.props.plugin.version === this.props.plugin.currentVersion) {
      return { ...styles.pluginBox };
    } else {
      return {
        ...styles.pluginBox,
        ...styles.updateAvailableBorderColor,
      };
    }
  }

  updateVersion = () => {
    const newPlugin: IPluginExtensions = {
      ...this.props.pluginType,
      config: {
        ...this.props.pluginType.config,
        uri: Parser.replaceResourceVersion(
          this.props.plugin.resource,
          this.props.plugin.currentVersion,
        ),
      },
    };
    this.props.updatePlugin(this.props.pluginType.extensionKey, newPlugin);
  };

  getButtonName(type: string) {
    if (type === PluginType.PARSER) {
      return 'Add dictionary';
    } else {
      return `Add ${Parser.getPluginName(type, false)}`;
    }
  }

  openViewJsonModal = () => {
    if (!_.isEmpty(this.props.pluginResource)) {
      ModalActionDispatchers.showViewJsonModal(this.props.pluginResource);
    }
  };

  render() {
    const { plugin } = this.props;
    const isCurrentVersion: boolean =
      plugin && plugin.version === plugin.currentVersion;
    let pluginLatestVersion = 'v01';
    if (!isCurrentVersion) {
      pluginLatestVersion = Parser.getVersionString(plugin.currentVersion);
    }
    return (
      <div style={styles.pluginContainer}>
        {renderIf(!this.props.editDisabled)(() => (
          <div
            onClick={() => {
              this.deletePlugin(this.props.pluginType.extensionKey);
            }}
            style={styles.closeButton}>
            &times;
          </div>
        ))}
        <button style={this.getBoxStyling()} onClick={this.openViewJsonModal}>
          <div style={styles.pluginHeader}>
            <div style={this.getNameStyling()}>
              {PluginHelper.getName(
                this.props.pluginType.type,
                this.props.plugin,
                true,
              )}
            </div>
            <div style={styles.pluginVersion}>
              {PluginHelper.getVersion(
                this.props.pluginType.type,
                this.props.plugin,
                true,
              )}
            </div>
          </div>
          <div style={styles.pluginDate}>{this.props.pluginType.type}</div>
          <div style={styles.pluginDate}>
            {PluginHelper.getLastModified(
              this.props.pluginType.type,
              this.props.plugin,
              true,
              <br />,
            )}
          </div>
        </button>
        {renderIf(!isCurrentVersion && !this.props.editDisabled)(() => (
          <WhiteButton
            onClick={this.updateVersion}
            text={`Update to ${pluginLatestVersion}`}
            customStyles={styles.updateAvailableButton}
          />
        ))}
        {renderIf(!this.props.editDisabled)(() => (
          <div
            onClick={this.openAddPluginsModal}
            style={styles.addResourceButton}>
            {this.getButtonName(this.props.pluginType.type)}
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
