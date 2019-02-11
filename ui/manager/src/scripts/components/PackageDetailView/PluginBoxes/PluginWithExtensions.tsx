import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IOptions } from '../PackageView';
import Parser from '../../utils/Parser';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import { CSSProperties } from 'react';
import styles from './Plugin.styles';
import Extension from './Extension';
import {
  IDefaultPluginTypes,
  IPlugin,
  IPluginExtensions,
  IPluginTypes,
} from '../../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { getDate } from '../../utils/DateFormat';
import { ModalEnum } from '../../utils/ModalEnum';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as PluginType from '../../utils/EddiTypes';
import PluginHelper from '../../utils/helpers/PluginHelper';
import * as Radium from 'radium';
import { REGULAR_DICTIONARY } from '../../utils/EddiTypes';

const customStyles: CSSProperties = {
  extensionList: {
    display: 'grid',
    marginTop: '10px',
    marginLeft: '10px',
    marginRight: '10px',
    marginBottom: '10px',
    gridGap: '10px 20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
};

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

class PluginWithExtensions extends React.Component<IPrivateProps> {
  componentDidMount() {
    if (this.props.pluginResource) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
    let dictionaries: IOptions[] = [];
    let corrections: IOptions[] = [];
    if (!_.isEmpty(this.props.pluginType.extensions)) {
      if (!_.isEmpty(this.props.pluginType.extensions.dictionaries)) {
        dictionaries = this.props.pluginType.extensions.dictionaries.map(
          (d, i) => ({
            ...d,
            extensionKey: i,
          }),
        );
      }
      if (!_.isEmpty(this.props.pluginType.extensions.corrections)) {
        corrections = this.props.pluginType.extensions.corrections.map(
          (c, i) => ({
            ...c,
            extensionKey: i,
          }),
        );
      }
    }
    this.setState({
      selectedExtensions: { dictionaries, corrections },
    });
  }

  deletePlugin = () => {
    this.props.deletePlugin(this.props.pluginType.extensionKey);
  };

  deleteExtension = (extensionKey: number) => {
    // todo: refactor this
    /*const newExtensionList = this.props.pluginType.extensions
      .filter(e => e.extensionKey !== extensionKey)
      .map((ext, i) => {
        return { type: ext.type, resource: ext.resource, extensionKey: i };
      });
    this.props.updateExtensionsInPlugin(
      this.props.pluginType.extensionKey,
      newExtensionList,
    );*/
  };

  selectExtensions = (newPluginResourceList: string[]) => {
    // todo: REFACTOR THIS
    /*
    switch (this.props.pluginType.type) {
      case PluginType.PARSER:
        const newExtensionList = newPluginResourceList.map((ext, i) => {
          return {
            type: PluginType.REGULAR_DICTIONARY,
            resource: ext,
            extensionKey: i,
          };
        });
        this.props.updateExtensionsInPlugin(
          this.props.pluginType.extensionKey,
          newExtensionList,
        );
        return;
      case PluginType.BEHAVIOR:
        break;
      case PluginType.OUTPUT:
        break;
      default:
        return;
    }
    const newExtensionList = newPluginResourceList.map((ext, i) => {
      return {
        type: PluginType.REGULAR_DICTIONARY,
        resource: ext,
        extensionKey: i,
      };
    });
    this.props.updateExtensionsInPlugin(
      this.props.pluginType.extensionKey,
      newExtensionList,
    );*/
  };

  updatePluginResource = (newPluginResourceList: string[]) => {
    // todo: REFACTOR THIS!
    console.log(newPluginResourceList[0]);
    if (this.props.pluginType.type === PluginType.PARSER) {
      const otherDictionaries = this.props.pluginType.extensions.dictionaries.filter(
        d => {
          return { ...d };
        },
      );
      const newRegularDictionaryList = newPluginResourceList.map(resource => {
        return { type: REGULAR_DICTIONARY, config: { uri: resource } };
      });
      const newPlugin: IPluginExtensions = {
        ...this.props.pluginType,
        extensions: {
          dictionaries: otherDictionaries.concat(newRegularDictionaryList),
          corrections: this.props.pluginType.extensions.corrections,
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

  openAddPluginsModal = () => {
    console.log('test1');
    let extensionList: string[];
    let pluginType;
    if (this.props.pluginType.type === PluginType.PARSER) {
      if (
        this.props.pluginType.config &&
        !_.isEmpty(this.props.pluginType.extensions.dictionaries)
      ) {
        console.log('WHOPS');
        extensionList = this.props.pluginType.extensions.dictionaries.map(p => {
          return p.config.uri;
        });
      }
      pluginType = PluginType.REGULAR_DICTIONARY;
    } else {
      pluginType = this.props.pluginType.type;
      extensionList =
        (this.props.pluginType.config.uri && [
          this.props.pluginType.config.uri,
        ]) ||
        [];
    }
    console.log('test');
    ModalActionDispatchers.showAddPluginsModal(
      pluginType,
      extensionList,
      this.updatePluginResource,
    );
  };

  updateExtension = (extensionResource: string) => {
    // todo: REFACTOR THIS
    /*
    const newExtensionList = this.props.pluginType.extensions.dictionary.map(ext => {
      return {
        type: ext.type,
        config: ext.config,
        extensionKey: ext.extensionKey,
      };
    });
    this.props.updateExtensionsInPlugin(
      this.props.pluginType.extensionKey,
      newExtensionList,
    );*/
  };

  getResource(plugin: IPluginExtensions) {
    if (plugin.config) {
      return plugin.config.uri;
    }
    return null;
  }

  render() {
    return (
      <div style={styles.pluginWithExtensionsContainer}>
        {renderIf(!this.props.editDisabled)(() => (
          <div style={styles.closeButton} onClick={this.deletePlugin}>
            &times;
          </div>
        ))}
        <div style={styles.pluginBoxWithExtensions}>
          <div style={styles.bigPluginName}>
            <div style={styles.pluginName}>
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
            <div style={styles.centerFlex} />
            {renderIf(!this.props.editDisabled)(() => (
              <a
                onClick={this.openAddPluginsModal}
                style={styles.addExtensionButton}>
                {'Add dictionary'}
              </a>
            ))}
          </div>
          <div style={styles.pluginDate}>
            {PluginHelper.getLastModified(
              this.props.pluginType.type,
              this.props.plugin,
              true,
              '',
            )}
          </div>
          <div style={styles.pluginDate}>{this.props.pluginType.type}</div>
          {renderIf(!_.isEmpty(this.props.pluginType.extensions))(() => (
            <div>
              <div style={customStyles.extensionList}>
                {this.props.pluginType.extensions.dictionaries.map((ext, i) => (
                  <Extension
                    key={i}
                    index={i}
                    pluginType={ext}
                    deleteExtension={this.deleteExtension}
                    pluginResource={this.getResource(ext)}
                    updateExtension={this.updateExtension}
                    editDisabled={this.props.editDisabled}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedPluginWithExtensions: Component<IProps, IProps> = compose<IProps>(
  pure,
  setDisplayName('PluginWithExtensions'),
)(PluginWithExtensions);

export default ComposedPluginWithExtensions;
