import * as React from 'react';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IOptions } from '../PackageView';
import Parser from '../../utils/Parser';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import { IPlugin } from '../../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import styles from './Plugin.styles';
import { connect } from 'react-redux';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import { ModalEnum } from '../../utils/ModalEnum';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import * as PluginType from '../../utils/EddiTypes';
import PluginHelper from '../../utils/helpers/PluginHelper';

interface IPublicProps {
  pluginType: IOptions;
  pluginResource: string;
  editDisabled: boolean;
  deletePlugin(extensionKey: number): void;
  updateExtensionsInPlugin(extensionKey: number, extensions: IOptions[]): void;
  updateResourceInPlugin(extensionKey: number, newResource: string): void;
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
    let extensionList: string[];
    let pluginType;
    if (this.props.pluginType.type === PluginType.PARSER) {
      extensionList = this.props.pluginType.extensions.map(p => {
        return p.resource;
      });
      pluginType = PluginType.REGULAR_DICTIONARY;
    } else {
      pluginType = this.props.pluginType.type;
      extensionList =
        (this.props.pluginType.resource && [this.props.pluginType.resource]) ||
        [];
    }
    ModalActionDispatchers.showAddPluginsModal(
      pluginType,
      extensionList,
      this.selectExtensions,
    );
  };

  selectExtensions = (newPluginResourceList: string[]) => {
    if (this.props.pluginType.type === PluginType.PARSER) {
      const newExtensionList: IOptions[] = newPluginResourceList.map(
        (ext, i) => {
          return {
            type: PluginType.REGULAR_DICTIONARY,
            resource: ext,
            extensionKey: i,
          };
        },
      );
      this.props.updateExtensionsInPlugin(
        this.props.pluginType.extensionKey,
        newExtensionList,
      );
    } else if (!_.isEmpty(newPluginResourceList)) {
      this.props.updateResourceInPlugin(
        this.props.pluginType.extensionKey,
        newPluginResourceList[0],
      );
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
    this.props.updateResourceInPlugin(
      this.props.pluginType.extensionKey,
      Parser.replaceResourceVersion(
        this.props.plugin.resource,
        this.props.plugin.currentVersion,
      ),
    );
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
        <div
          onClick={() => {
            this.deletePlugin(this.props.pluginType.extensionKey);
          }}
          style={styles.closeButton}>
          &times;
        </div>
        <button style={this.getBoxStyling()} onClick={this.openViewJsonModal}>
          <div style={styles.pluginHeader}>
            <div style={this.getNameStyling()}>
              {PluginHelper.getName(
                this.props.pluginType,
                this.props.plugin,
                true,
              )}
            </div>
            <div style={styles.pluginVersion}>
              {PluginHelper.getVersion(
                this.props.pluginType,
                this.props.plugin,
                true,
              )}
            </div>
          </div>
          <div style={styles.pluginDate}>{this.props.pluginType.type}</div>
          <div style={styles.pluginDate}>
            {PluginHelper.getLastModified(
              this.props.pluginType,
              this.props.plugin,
              true,
              <br />,
            )}
          </div>
        </button>
        {renderIf(!isCurrentVersion)(() => (
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
