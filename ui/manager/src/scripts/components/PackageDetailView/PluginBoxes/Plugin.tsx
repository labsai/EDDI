import * as _ from 'lodash';
import Radium from 'radium';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import SquareXButton from '../../Assets/Buttons/SquareXButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { IPlugin, IPluginExtensions } from '../../utils/AxiosFunctions';
import * as PluginType from '../../utils/EddiTypes';
import { REGULAR_DICTIONARY } from '../../utils/EddiTypes';
import PluginHelper from '../../utils/helpers/PluginHelper';
import Parser from '../../utils/Parser';
import { IOptions } from '../PackageView';
import styles from './Plugin.styles';

interface IPublicProps {
  pluginType: IOptions;
  pluginResource?: string;
  editDisabled: boolean;
  deletePlugin?: (extensionKey: number) => void;
  updatePlugin?: (extensionKey: number, newPlugin: IPluginExtensions) => void;
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

  componentDidUpdate(prevProps) {
    if (
      this.props.pluginResource &&
      this.props.pluginResource !== prevProps.pluginResource
    ) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  deletePlugin = () => {
    this.props.deletePlugin(this.props.pluginType.extensionKey);
  };

  openAddPluginsModal = (e) => {
    e.stopPropagation();
    let extensionList: string[] = [];
    let pluginType;
    if (this.props.pluginType.type === PluginType.PARSER) {
      if (
        this.props.pluginType.config &&
        !_.isEmpty(this.props.pluginType.extensions.dictionaries)
      ) {
        extensionList = this.props.pluginType.extensions.dictionaries.map(
          (p) => {
            return p.config.uri;
          },
        );
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
    if (this.props.pluginType.type === PluginType.PARSER) {
      let otherDictionaries = [];
      if (
        !_.isEmpty(this.props.pluginType.extensions) &&
        !_.isEmpty(this.props.pluginType.extensions.dictionaries)
      ) {
        otherDictionaries =
          this.props.pluginType.extensions.dictionaries.filter(
            (d) => d.type !== REGULAR_DICTIONARY,
          );
      }
      const newRegularDictionaryList = newPluginResourceList.map((resource) => {
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
      if (!_.isEmpty(this.props.plugin)) {
        return { ...styles.pluginBox, ...styles.clickablePluginBox };
      } else {
        return { ...styles.pluginBox };
      }
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
    let pluginCurrentVersion = 'v01';
    if (!isCurrentVersion) {
      pluginCurrentVersion = Parser.getVersionString(plugin.currentVersion);
    }
    return (
      <div style={styles.pluginContainer}>
        {!this.props.editDisabled && (
          <SquareXButton
            customStyles={styles.closeButton}
            onClick={this.deletePlugin}
          />
        )}
        <button style={this.getBoxStyling()} onClick={this.openViewJsonModal}>
          <div style={styles.pluginHeader}>
            <div key={'pluginBox'} style={this.getNameStyling()}>
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
            {!this.props.editDisabled && (
              <div
                style={styles.addResourceButton}
                key={'addResource'}
                onClick={this.openAddPluginsModal}>
                {this.getButtonName(this.props.pluginType.type)}
              </div>
            )}
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
        {!isCurrentVersion && !this.props.editDisabled && (
          <WhiteButton
            onClick={this.updateVersion}
            text={`Update to ${pluginCurrentVersion}`}
            customStyles={styles.updateAvailableButton}
          />
        )}
      </div>
    );
  }
}

const ComposedPlugin: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(pluginSelector),
  Radium,
  setDisplayName('Plugin'),
)(Plugin);

export default ComposedPlugin;
