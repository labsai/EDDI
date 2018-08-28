import * as React from 'react';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import PackageDescriptor from './PackageDescriptor';
import Plugin from './PluginBoxes/Plugin';
import PluginWithExtension from './PluginBoxes/PluginWithExtensions';
import VersionDropDownComponent from '../BotDetailView/VersionDropDown/VersionDropDownComponent';
import * as renderIf from 'render-if';
import styles from './PackageView.styles';
import { Component, compose, pure, setDisplayName } from 'recompose';
import PluginSelect from './DropDownComponents/PluginSelect';
import * as _ from 'lodash';
import {
  IDefaultPluginTypes,
  IDetailedDescriptor,
  IPackage,
} from '../utils/AxiosFunctions';
import { ModalEnum } from '../utils/ModalEnum';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { connect } from 'react-redux';
import { pluginTypesSelector } from '../../selectors/PackageSelectors';
import { defaultPluginTypesSelector } from '../../selectors/PluginSelectors';
import BlueButton from '../Assets/Buttons/BlueButton';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import BotsUsingPackage from './UsedByComponent/BotsUsingPackage';

export interface IOptions {
  type: string;
  resource?: string;
  extensions?: IOptions[];
  extensionKey?: number;
}

interface IPublicProps {
  packagePayload: IPackage;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  data: string;
  defaultPluginTypes: IDefaultPluginTypes[];
  descriptor: IDetailedDescriptor;
}

interface IState {
  selectedPlugins: IOptions[];
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
      defaultPluginTypes: [],
      extensionKey: 0,
    };
  }

  componentDidMount() {
    eddiApiActionDispatchers.fetchPluginTypesAction(
      this.props.packagePayload.resource,
    );
    eddiApiActionDispatchers.fetchDefaultPluginTypesAction();
    this.discardChanges();
  }

  componentWillReceiveProps(nextProps) {
    if (!nextProps.packagePayload.packageData) {
      eddiApiActionDispatchers.fetchPackageDataAction(
        nextProps.packagePayload.resource,
      );
    }
    this.discardChanges(nextProps);
    this.setState({ defaultPluginTypes: nextProps.defaultPluginTypes });
  }

  openEditPackageModal = () => {
    ModalActionDispatchers.showEditPackageModal(this.props.packagePayload);
  };

  openEditJsonModal = () => {
    ModalActionDispatchers.showEditJsonModal(
      this.props.packagePayload.resource,
      this.props.packagePayload.packageData,
    );
  };

  discardChanges(props = this.props): void {
    this.setState({
      selectedPlugins: props.packagePayload.pluginTypes.map((o, i) => ({
        type: o.type,
        resource: o.resource,
        extensions: !_.isEmpty(o.extensions)
          ? o.extensions.map((e, key) => {
              return { ...e, extensionKey: key };
            })
          : [],
        extensionKey: i,
      })),
      extensionKey: props.packagePayload.pluginTypes.length,
    });
  }

  addPlugin = (addedExtension: IOptions) => {
    const newPluginList = this.state.selectedPlugins.map((plugin, i) => ({
      type: plugin.type,
      resource: plugin.resource,
      extensions: !_.isEmpty(plugin.extensions)
        ? plugin.extensions.map(extension => {
            return extension;
          })
        : [],
      extensionKey: i,
    }));
    newPluginList.push({
      type: addedExtension.type,
      resource: null,
      extensions: [],
      extensionKey: this.state.extensionKey,
    });
    this.setState({
      selectedPlugins: newPluginList,
      extensionKey: newPluginList.length,
    });
  };

  saveExtensions = async () => {
    const list = this.state.selectedPlugins.map(selected => {
      if (
        selected.type === 'eddi://ai.labs.parser' &&
        selected.extensions.find(ext => !!ext.resource)
      ) {
        return {
          type: selected.type,
          config: { uri: selected.resource },
          extensions: {
            dictionaries: selected.extensions.map(ext => ({
              type: ext.type,
              config: { uri: ext.resource },
            })),
          },
        };
      } else if (!selected.resource) {
        return {
          type: selected.type,
        };
      } else {
        return {
          type: selected.type,
          config: { uri: selected.resource },
        };
      }
    });
    eddiApiActionDispatchers.updatePluginTypeAction(
      this.props.packagePayload.resource,
      list,
    );
  };

  static filterExtension = (data: IOptions[], extensionKey: number) => {
    return data.filter(ext => !_.isEqual(ext.extensionKey, extensionKey));
  };

  deletePlugin = (extensionKey: number) => {
    const newPluginList = this.state.selectedPlugins
      .filter(plugin => plugin.extensionKey !== extensionKey)
      .map((plugin, i) => ({
        type: plugin.type,
        resource: plugin.resource,
        extensions: !_.isEmpty(plugin.extensions)
          ? plugin.extensions.map(extension => {
              return extension;
            })
          : [],
        extensionKey: i,
      }));
    this.setState({
      selectedPlugins: newPluginList,
      extensionKey: newPluginList.length,
    });
  };

  updateResourceInPlugin = (pluginKey: number, newResource: string) => {
    const newPluginList = this.state.selectedPlugins.map((plugin, i) => {
      return {
        type: plugin.type,
        resource:
          pluginKey === plugin.extensionKey ? newResource : plugin.resource,
        extensions: plugin.extensions.map(ext => ext),
        extensionKey: i,
      };
    });
    this.setState({
      selectedPlugins: newPluginList,
      extensionKey: newPluginList.length,
    });
  };

  updateExtensionsInPlugin = (pluginKey: number, extensions: IOptions[]) => {
    const newPluginList = this.state.selectedPlugins.map((plugin, i) => {
      if (plugin.extensionKey === pluginKey) {
        return {
          type: plugin.type,
          resource: plugin.resource,
          extensions,
          extensionKey: i,
        };
      } else {
        return {
          type: plugin.type,
          resource: plugin.resource,
          extensions: !_.isEmpty(plugin.extensions)
            ? plugin.extensions.map(extension => {
                return extension;
              })
            : [],
          extensionKey: i,
        };
      }
    });
    this.setState({
      selectedPlugins: newPluginList,
      extensionKey: newPluginList.length,
    });
  };

  unsavedChanges(): boolean {
    if (
      _.size(this.state.selectedPlugins) ===
      _.size(this.props.packagePayload.pluginTypes)
    ) {
      for (let i = 0; i < this.state.selectedPlugins.length; i++) {
        if (
          this.state.selectedPlugins[i].type ===
            this.props.packagePayload.pluginTypes[i].type &&
          this.state.selectedPlugins[i].resource ===
            this.props.packagePayload.pluginTypes[i].resource
        ) {
          if (
            _.size(this.state.selectedPlugins[i].extensions) ===
            _.size(this.props.packagePayload.pluginTypes[i].extensions)
          ) {
            for (
              let e = 0;
              e < this.state.selectedPlugins[i].extensions.length;
              e++
            ) {
              if (
                this.state.selectedPlugins[i].extensions[e].resource !==
                this.props.packagePayload.pluginTypes[i].extensions[e].resource
              ) {
                return true;
              }
            }
          } else {
            return true;
          }
        } else {
          return true;
        }
      }
      return false;
    } else {
      return true;
    }
  }

  buttonDisabled() {
    return !this.unsavedChanges();
  }

  render() {
    return (
      <div>
        <div style={styles.packageHeader}>
          <div style={styles.packageName}>
            {this.props.packagePayload.name || this.props.packagePayload.id}
          </div>
          <VersionDropDownComponent
            version={this.props.packagePayload.version}
          />
          <WhiteButton
            onClick={this.openEditPackageModal}
            text={'Edit Package'}
            customStyles={styles.editPackageButton}
          />
          <WhiteButton
            onClick={this.openEditJsonModal}
            text={'Edit JSON'}
            customStyles={styles.editPackageButton}
          />
          {renderIf(foundUnpublishedChanges)(() => (
            <div style={styles.unpublishedChanges}>
              <img src={warningIcon} style={styles.warningIcon} />
              <div style={styles.unpublishedChangesText}>
                {'This Package has unpublished changes'}
              </div>
            </div>
          ))}
          <div style={styles.packageHeaderSpacing} />
          {renderIf(this.unsavedChanges())(() => (
            <button
              style={styles.discardChanges}
              onClick={() => this.discardChanges()}>
              {'Discard changes'}
            </button>
          ))}
          <BlueButton
            text={'Save'}
            disabled={this.buttonDisabled()}
            onClick={this.saveExtensions}
          />
        </div>
        <PackageDescriptor packagePayload={this.props.packagePayload} />
        <div style={styles.usedInBotsTitle}>
          {'Used in bots'}
          <BotsUsingPackage packagePayload={this.props.packagePayload} />
        </div>
        {renderIf(this.state.selectedPlugins)(() => (
          <div>
            {this.state.selectedPlugins
              .filter(p => _.size(p.extensions) > 0)
              .map((ext, key) => (
                <PluginWithExtension
                  key={key}
                  pluginType={ext}
                  pluginResource={ext.resource}
                  deletePlugin={this.deletePlugin}
                  updateExtensionsInPlugin={this.updateExtensionsInPlugin}
                />
              ))}
            <div style={styles.pluginList}>
              {this.state.selectedPlugins
                .filter(p => _.size(p.extensions) === 0)
                .map((ext, key) => (
                  <Plugin
                    key={key}
                    pluginType={ext}
                    deletePlugin={this.deletePlugin}
                    pluginResource={ext.resource}
                    updateResourceInPlugin={this.updateResourceInPlugin}
                    updateExtensionsInPlugin={this.updateExtensionsInPlugin}
                  />
                ))}
            </div>
          </div>
        ))}
        <div style={styles.pluginAddTitle}>{'Add plugins'}</div>
        <div style={styles.pluginDropdown}>
          <PluginSelect
            packageExtensions={this.state.defaultPluginTypes.map(extension => {
              return extension;
            })}
            addExtension={this.addPlugin}
          />
        </div>
      </div>
    );
  }
}

const ComposedPackageView: Component<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(pluginTypesSelector),
  connect(defaultPluginTypesSelector),
  setDisplayName('PackageView'),
)(PackageView);

export default ComposedPackageView;
