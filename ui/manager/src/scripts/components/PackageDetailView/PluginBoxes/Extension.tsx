import * as React from 'react';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IOptions } from '../PackageView';
import Parser from '../../utils/Parser';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import { CSSProperties } from 'react';
import styles from './Plugin.styles';
import { IPlugin } from '../../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { getDate } from '../../utils/DateFormat';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import { connect } from 'react-redux';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import PluginHelper from '../../utils/helpers/PluginHelper';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';

const customStyles: CSSProperties = {
  closeButton: {
    ':focus': {
      color: '#FF5976',
      border: '1px solid #FF5976',
    },
    ':hover': {
      color: '#FF5976',
      border: '1px solid #FF5976',
    },
    backgroundColor: '#FFF',
    color: '#D8DDE6',
    cursor: 'pointer',
    float: 'right',
    fontSize: '25px',
    position: 'relative',
    right: '8px',
    border: '1px solid #D8DDE6',
    borderRadius: '4px',
    height: '23px',
    width: '23px',
    textAlign: 'center',
    lineHeight: '23px',
  },
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    marginLeft: '5px',
    marginRight: '5px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
};

interface IPublicProps {
  pluginType: IOptions;
  index: number;
  pluginResource: string;
  editDisabled: boolean;
  deleteExtension(extensionKey: number): void;
  updateExtension(extensionResource: string): void;
}

interface IPrivateProps extends IPublicProps {
  plugin: IPlugin;
}

class Extension extends React.Component<IPrivateProps> {
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

  deleteExtension = () => {
    this.props.deleteExtension(this.props.index);
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
      return (this.props.plugin && this.props.plugin.name) || 'Dictionary';
    } else {
      return 'Dictionary';
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
      return { ...styles.extensionBox };
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
          <div style={customStyles.closeButton} onClick={this.deleteExtension}>
            &times;
          </div>
        ))}
        <button style={this.getBoxStyling()} onClick={this.openViewJsonModal}>
          <div style={styles.pluginHeader}>
            <div style={this.getNameStyling()}>{this.getPluginName()}</div>
            <div style={styles.pluginVersion}>
              {PluginHelper.getVersion(
                this.props.pluginType,
                this.props.plugin,
                true,
              )}
            </div>
          </div>
          <div style={styles.pluginDate}>
            {PluginHelper.getLastModified(
              this.props.pluginType,
              this.props.plugin,
              true,
              <br />,
            )}
          </div>
          <div style={styles.pluginDate}>
            {Parser.getExtensionType(this.props.pluginType.type)}
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

const ComposedExtension: Component<IProps, IProps> = compose<IProps>(
  pure,
  connect(pluginSelector),
  setDisplayName('Extension'),
)(Extension);

export default ComposedExtension;
