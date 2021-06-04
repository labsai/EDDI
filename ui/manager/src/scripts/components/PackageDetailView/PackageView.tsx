import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import { defaultPluginTypesSelector } from '../../selectors/PluginSelectors';
import BlueButton from '../Assets/Buttons/BlueButton';
import Options from '../Assets/Buttons/Options';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import {
  IDefaultPluginTypes,
  IPackage,
  IPluginExtensions,
} from '../utils/AxiosFunctions';
import { PACKAGE } from '../utils/EddiTypes';
import PluginHelper from '../utils/helpers/PluginHelper';
import { hasExtensions } from '../utils/helpers/PluginParser';
import Parser from '../utils/Parser';
import PluginSelect from './DropDownComponents/PluginSelect';
import PackageDescriptor from './PackageDescriptor';
import styles from './PackageView.styles';
import Plugin from './PluginBoxes/Plugin';
import PluginWithExtension from './PluginBoxes/PluginWithExtensions';
import BotsUsingPackage from './UsedByComponent/BotsUsingPackage';

export interface IOptions extends IPluginExtensions {
  extensionKey?: number;
}

interface IPublicProps {
  packagePayload: IPackage;
}

interface IPrivateProps extends IPublicProps {
  defaultPluginTypes: IDefaultPluginTypes[];
  readOnly: boolean;
}

interface IState {
  selectedPlugins: IOptions[];
  initialSelectedPluginState: IOptions[];
  defaultPluginTypes: IDefaultPluginTypes[];
  extensionKey: number;
}

const warningIcon = require('../../../public/images/WarningIcon.png');
const foundUnpublishedChanges = false; // todo : add function to check if there are unpublished changes.

class PackageView extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedPlugins: [],
      initialSelectedPluginState: [],
      defaultPluginTypes: [],
      extensionKey: 0,
    };
  }

  async componentDidMount() {
    eddiApiActionDispatchers.fetchPackageDataAction(
      this.props.packagePayload.resource,
    );
    eddiApiActionDispatchers.fetchDefaultPluginTypesAction();
    this.discardChanges();
  }

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      if (!this.props.packagePayload.packageData) {
        eddiApiActionDispatchers.fetchPackageDataAction(
          this.props.packagePayload.resource,
        );
      }
      if (
        _.isEmpty(prevProps.packagePayload.packageData) &&
        !_.isEmpty(this.props.packagePayload.packageData)
      ) {
        this.discardChanges(this.props);
      }
      this.setState({ defaultPluginTypes: this.props.defaultPluginTypes });
    }
  }

  openEditPackageModal = () => {
    ModalActionDispatchers.showEditDescriptorModalAction(
      this.props.packagePayload,
    );
  };

  replacer(key, value) {
    if (key === 'extensionKey') {
      return undefined;
    } else {
      return value;
    }
  }

  openEditJsonModal = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(PACKAGE);
    ModalActionDispatchers.showEditJsonModal(
      this.props.packagePayload.resource,
      JSON.stringify(
        { packageExtensions: this.state.selectedPlugins },
        this.replacer,
        '\t',
      ),
    );
  };

  discardChanges(props = this.props): void {
    if (_.isUndefined(props.packagePayload.packageData)) {
      return;
    }
    const initialSelectedPluginState =
      props.packagePayload.packageData.packageExtensions.map((o, i) => ({
        ...o,
        extensionKey: i,
      }));
    if (!_.isUndefined(props.packagePayload.packageData)) {
      this.setState({
        selectedPlugins: initialSelectedPluginState,
        initialSelectedPluginState: initialSelectedPluginState,
        extensionKey: props.packagePayload.packageData.packageExtensions.length,
      });
    }
  }

  addPlugin = (addedExtension: IPluginExtensions) => {
    const newPluginList = this.state.selectedPlugins.map((plugin, i) => ({
      ...plugin,
      extensionKey: i,
    }));
    newPluginList.push({
      ...addedExtension,
      extensionKey: this.state.extensionKey,
    });
    this.setState({
      selectedPlugins: newPluginList,
      extensionKey: newPluginList.length,
    });
  };

  saveChanges = async () => {
    const list = this.state.selectedPlugins.map((selected) => {
      return { ...selected };
    });
    eddiApiActionDispatchers.updateJsonDataAction(
      this.props.packagePayload.resource,
      { packageExtensions: list },
    );
  };

  static filterExtension = (data: IOptions[], extensionKey: number) => {
    return data.filter((ext) => !_.isEqual(ext.extensionKey, extensionKey));
  };

  deletePlugin = (extensionKey: number) => {
    const newPluginList = this.state.selectedPlugins
      .filter((plugin) => plugin.extensionKey !== extensionKey)
      .map((plugin, i) => ({
        type: plugin.type,
        extensions: plugin.extensions,
        config: plugin.config,
        extensionKey: i,
      }));
    this.setState({
      selectedPlugins: newPluginList,
      extensionKey: newPluginList.length,
    });
  };

  updatePlugin = (pluginKey: number, newPlugin: IOptions) => {
    const newPluginList = this.state.selectedPlugins.map((plugin) => {
      if (plugin.extensionKey === pluginKey) {
        return { ...newPlugin };
      } else {
        return { ...plugin };
      }
    });
    this.setState({
      selectedPlugins: newPluginList,
      extensionKey: newPluginList.length,
    });
  };

  unsavedChanges(): boolean {
    return !_.isEqual(
      JSON.stringify(this.state.selectedPlugins),
      JSON.stringify(this.state.initialSelectedPluginState),
    );
  }

  selectVersion = (newVersion: number) => {
    eddiApiActionDispatchers.fetchPackageAction(
      Parser.replaceResourceVersion(
        this.props.packagePayload.resource,
        newVersion,
      ),
    );
    if (newVersion === this.props.packagePayload.currentVersion) {
      historyPush(`/packageview/${this.props.packagePayload.id}`);
    } else {
      historyPush(`/packageview/${this.props.packagePayload.id}`, [
        `version=${newVersion}`,
      ]);
    }
  };

  render() {
    const isCurrentVersion =
      this.props.packagePayload.version ===
      this.props.packagePayload.currentVersion;
    return (
      <div>
        <div style={styles.packageHeader}>
          <div style={styles.packageName}>
            {this.props.packagePayload.name || this.props.packagePayload.id}
          </div>
          <VersionSelectComponent
            selectedVersion={this.props.packagePayload.version}
            currentVersion={this.props.packagePayload.currentVersion}
            selectVersion={this.selectVersion}
          />
          <WhiteButton
            onClick={this.openEditPackageModal}
            text={'Rename'}
            customStyles={styles.editPackageButton}
            disabled={!isCurrentVersion || this.props.readOnly}
          />
          <WhiteButton
            onClick={this.openEditJsonModal}
            text={'Edit JSON'}
            customStyles={styles.editPackageButton}
            disabled={!isCurrentVersion || this.props.readOnly}
          />
          {foundUnpublishedChanges && (
            <div style={styles.unpublishedChanges}>
              <img src={warningIcon} style={styles.warningIcon} />
              <div style={styles.unpublishedChangesText}>
                {'This Package has unpublished changes'}
              </div>
            </div>
          )}
          <div style={styles.packageHeaderSpacing} />
          {this.unsavedChanges() && (
            <button
              style={styles.discardChanges}
              onClick={() => this.discardChanges()}>
              {'Discard changes'}
            </button>
          )}
          <div style={styles.options}>
            <Options
              descriptor={this.props.packagePayload}
              data={JSON.stringify(
                this.props.packagePayload.packageData,
                null,
                '\t',
              )}
            />
          </div>
          <BlueButton
            text={'Save'}
            disabled={!this.unsavedChanges()}
            onClick={this.saveChanges}
          />
        </div>
        <PackageDescriptor packagePayload={this.props.packagePayload} />
        <div style={styles.usedInBotsTitle}>
          {'Used in bots'}
          <BotsUsingPackage packagePayload={this.props.packagePayload} />
        </div>
        {!!this.state.selectedPlugins &&
          !_.isEmpty(this.state.selectedPlugins) && (
            <div>
              {this.state.selectedPlugins
                .filter((p) => hasExtensions(p))
                .map((ext, key) => (
                  <PluginWithExtension
                    key={key}
                    pluginType={ext}
                    pluginResource={PluginHelper.getResource(ext)}
                    deletePlugin={this.deletePlugin}
                    updatePlugin={this.updatePlugin}
                    editDisabled={!isCurrentVersion || this.props.readOnly}
                  />
                ))}
              <div style={styles.pluginList}>
                {this.state.selectedPlugins
                  .filter((p) => !hasExtensions(p))
                  .map((ext, key) => (
                    <Plugin
                      key={key}
                      pluginType={ext}
                      deletePlugin={this.deletePlugin}
                      pluginResource={PluginHelper.getResource(ext)}
                      updatePlugin={this.updatePlugin}
                      editDisabled={!isCurrentVersion || this.props.readOnly}
                    />
                  ))}
              </div>
            </div>
          )}
        {isCurrentVersion && !this.props.readOnly && (
          <div>
            <div style={styles.pluginAddTitle}>{'Add plugins'}</div>
            <div style={styles.pluginDropdown}>
              <PluginSelect
                packageExtensions={this.state.defaultPluginTypes.map(
                  (extension) => {
                    return extension;
                  },
                )}
                addExtension={this.addPlugin}
              />
            </div>
          </div>
        )}
      </div>
    );
  }
}

const ComposedPackageView: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(defaultPluginTypesSelector),
  connect(readOnlySelector),
  setDisplayName('PackageView'),
)(PackageView);

export default ComposedPackageView;
