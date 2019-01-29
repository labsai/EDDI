import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
import {
  createNewPackage,
  getAllDefaultPluginTypes,
  IDefaultPluginTypes,
} from '../utils/AxiosFunctions';
import PluginSelect from '../PackageDetailView/DropDownComponents/PluginSelect';
import * as renderIf from 'render-if';
import Plugin from '../PackageDetailView/PluginBoxes/Plugin';
import { IOptions } from '../PackageDetailView/PackageView';
import * as _ from 'lodash';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { connect } from 'react-redux';
import { defaultPluginTypesSelector } from '../../selectors/PluginSelectors';
import { history } from '../../history';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';

const customStyles = {
  createNewBotButton: {
    backgroundColor: '#0070D2',
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    cursor: 'pointer',
    fontSize: '12px',
    height: '36px',
    marginLeft: '32px',
    marginTop: '8px',
    textAlign: 'center',
    minWidth: '100px',
  },
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    marginBottom: '20px',
    marginRight: '50px',
    marginLeft: '50px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
};

interface IPublicProps {
  packageName: string;
  packageDescription: string;
}
interface IPrivateProps extends IPublicProps {
  defaultPluginTypes: IDefaultPluginTypes[];
}
interface IState {
  addedPlugins: IOptions[];
  defaultPluginTypes: IDefaultPluginTypes[];
  extensionKey: number;
}

class UpdatePackageModal extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      addedPlugins: [],
      defaultPluginTypes: [],
      extensionKey: 0,
    };
  }
  async componentDidMount() {
    eddiApiActionDispatchers.fetchDefaultPluginTypesAction();
  }
  getButtonStyle() {
    if (!this.props.packageName) {
      return { ...customStyles.createNewBotButton, backgroundColor: '#c4c9d2' };
    } else {
      return { ...customStyles.createNewBotButton, backgroundColor: '#0070D2' };
    }
  }

  createNewPackage = async () => {
    const list: object = this.state.addedPlugins.map(a => ({
      type: a.type,
    }));
    const pkg = await createNewPackage(
      this.props.packageName,
      this.props.packageDescription,
      list,
    );
    eddiApiActionDispatchers.createNewPackageAction(pkg);
    return pkg;
  };

  addPluginsInModal = (addedPlugin: IOptions) => {
    const plugins = this.state.addedPlugins.concat({
      ...addedPlugin,
      extensionKey: this.state.extensionKey,
    });
    this.setState({
      addedPlugins: plugins,
      extensionKey: this.state.extensionKey + 1,
    });
  };
  deletePlugin = (extensionKey: number) => {
    this.setState({
      addedPlugins: this.state.addedPlugins.filter(
        ext => !_.isEqual(ext.extensionKey, extensionKey),
      ),
    });
  };

  render() {
    return (
      <div>
        <div style={styles.tallModalHeader}>
          <div style={styles.modalTopHeader}>
            <div style={styles.headerTextUpdate}> {this.props.packageName}</div>
            <div style={styles.modalTopHeaderCenter} />
            <button
              style={this.getButtonStyle()}
              onClick={async () => {
                const packageID = await this.createNewPackage();
                history.push(`/packageview/${packageID}`);
              }}>
              {'Save'}
            </button>
          </div>
          <div style={styles.modalBottomHeader}>
            <div style={styles.descriptionHeaderText}>
              <div style={styles.descriptorsUpdate}>
                {this.props.packageDescription}
              </div>
            </div>
          </div>
        </div>
        <div style={styles.updateModalContent}>
          {renderIf(this.state.addedPlugins)(() => (
            <div style={customStyles.pluginList}>
              {this.state.addedPlugins.map((extension, key) => (
                <Plugin
                  key={key}
                  pluginType={extension}
                  editDisabled={true}
                  deletePlugin={this.deletePlugin}
                />
              ))}
            </div>
          ))}
          <div style={styles.pluginText}>
            {'Add plugins'}
            <div style={styles.pluginSelector}>
              <PluginSelect
                packageExtensions={this.props.defaultPluginTypes.map(plugin => {
                  return plugin;
                })}
                addExtension={this.addPluginsInModal}
              />
            </div>
          </div>
        </div>
      </div>
    );
  }
}

const ComposedUpdatePackageModal: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('Modal'),
  connect(defaultPluginTypesSelector),
)(UpdatePackageModal);

export default ComposedUpdatePackageModal;
