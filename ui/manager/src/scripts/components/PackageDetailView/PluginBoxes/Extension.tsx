import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import Parser from '../../utils/Parser';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import styles from './Plugin.styles';
import { IPlugin, IPluginExtensions } from '../../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import { connect } from 'react-redux';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import SquareXButton from '../../Assets/Buttons/SquareXButton';
import PluginHelper from '../../utils/helpers/PluginHelper';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as Radium from 'radium';

interface IPublicProps {
  pluginType: IPluginExtensions;
  index: number;
  pluginResource: string;
  editDisabled: boolean;
  deleteExtension(extensionKey: number, type: string): void;
  updateExtension(extensionResource: string): void;
}

interface IPrivateProps extends IPublicProps {
  plugin: IPlugin;
}

class Extension extends React.Component<IPrivateProps> {
  componentDidMount() {
    if (!_.isEmpty(this.props.pluginResource)) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  componentDidUpdate(prevProps) {
    if (
      !_.isEmpty(this.props.pluginResource) &&
      this.props.pluginResource !== prevProps.pluginResource
    ) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  deleteExtension = () => {
    this.props.deleteExtension(this.props.index, this.props.pluginType.type);
  };

  updateVersion = () => {
    this.props.updateExtension(
      Parser.replaceResourceVersion(
        this.props.plugin.resource,
        this.props.plugin.currentVersion,
      ),
    );
  };

  getPluginName() {
    if (!_.isEmpty(this.props.plugin)) {
      return (
        (this.props.plugin && this.props.plugin.name) ||
        Parser.getPluginName(this.props.pluginType.type, true)
      );
    } else {
      return Parser.getPluginName(this.props.pluginType.type, true);
    }
  }

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
        return { ...styles.extensionBox, ...styles.clickablePluginBox };
      } else {
        return styles.extensionBox;
      }
    } else {
      return {
        ...styles.extensionBox,
        ...styles.updateAvailableBorderColor,
      };
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
      <div style={styles.extensionContainer}>
        {renderIf(!this.props.editDisabled)(() => (
          <SquareXButton
            customStyles={styles.closeButton}
            onClick={this.deleteExtension}
          />
        ))}
        <button style={this.getBoxStyling()} onClick={this.openViewJsonModal}>
          <div style={styles.pluginHeader}>
            <div style={this.getNameStyling()}>{this.getPluginName()}</div>
            <div style={styles.pluginVersion}>
              {PluginHelper.getVersion(
                this.props.pluginType.type,
                this.props.plugin,
                true,
              )}
            </div>
          </div>
          <div style={styles.pluginDate}>
            {Parser.getExtensionType(this.props.pluginType.type)}
          </div>
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
      </div>
    );
  }
}

const ComposedExtension: React.ComponentClass<IPublicProps> = compose<IPrivateProps, IPublicProps>(
  pure,
  connect(pluginSelector),
  setDisplayName('Extension'),
  Radium,
)(Extension);

export default ComposedExtension;
