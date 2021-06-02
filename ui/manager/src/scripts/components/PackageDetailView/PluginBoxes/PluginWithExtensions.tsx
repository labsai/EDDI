import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { IOptions } from '../PackageView';
import Parser from '../../utils/Parser';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import { CSSProperties } from 'react';
import styles from './Plugin.styles';
import Extension from './Extension';
import { IPlugin, IPluginExtensions } from '../../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as PluginType from '../../utils/EddiTypes';
import PluginHelper from '../../utils/helpers/PluginHelper';
import SquareXButton from '../../Assets/Buttons/SquareXButton';
import {
  CORRECTION,
  DICTIONARY,
  REGULAR_DICTIONARY,
} from '../../utils/EddiTypes';
import * as Radium from 'radium';

const customStyles: { [key: string]: IExtendedCSSProperties } = {
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
          (dictionary, i) => ({
            ...dictionary,
            extensionKey: i,
          }),
        );
      }
      if (!_.isEmpty(this.props.pluginType.extensions.corrections)) {
        corrections = this.props.pluginType.extensions.corrections.map(
          (correction, i) => ({
            ...correction,
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

  deleteExtension = (extensionKey: number, type: string) => {
    let dictionaries = [];
    let corrections = [];
    if (type.includes(CORRECTION)) {
      dictionaries = this.props.pluginType.extensions.dictionaries;
      corrections = this.props.pluginType.extensions.corrrections.splice(
        this.props.pluginType.extensions.corrections.findIndex(
          (c, i) => i === extensionKey,
        ),
        1,
      );
    } else if (type.includes(DICTIONARY)) {
      dictionaries = this.props.pluginType.extensions.dictionaries.splice(
        this.props.pluginType.extensions.dictionaries.findIndex(
          (d, i) => i === extensionKey,
        ),
        1,
      );
      corrections = this.props.pluginType.extensions.corrections;
    }
    const plugin: IPluginExtensions = {
      ...this.props.pluginType,
      extensions: {
        dictionaries,
        corrections,
      },
    };
    this.props.updatePlugin(this.props.pluginType.extensionKey, plugin);
  };

  updatePluginResource = (newPluginResourceList: string[]) => {
    if (this.props.pluginType.type === PluginType.PARSER) {
      const otherDictionaries = this.props.pluginType.extensions.dictionaries.filter(
        d => d.type !== REGULAR_DICTIONARY,
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
    let extensionList: string[];
    let pluginType;
    if (this.props.pluginType.type === PluginType.PARSER) {
      if (
        this.props.pluginType.config &&
        !_.isEmpty(this.props.pluginType.extensions.dictionaries)
      ) {
        extensionList = this.props.pluginType.extensions.dictionaries
          .filter(d => d.config && d.config.uri)
          .map(resource => {
            return resource.config.uri;
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
    ModalActionDispatchers.showAddPluginsModal(
      pluginType,
      extensionList,
      this.updatePluginResource,
    );
  };

  updateExtension = (extensionResource: string) => {
    const newExtensionList = this.props.pluginType.extensions.dictionaries.map(
      ext => {
        if (
          ext.config &&
          Parser.getId(ext.config.uri) === Parser.getId(extensionResource)
        ) {
          return {
            type: ext.type,
            config: { ...ext.config, uri: extensionResource },
          };
        } else {
          return ext;
        }
      },
    );
    const plugin: IPluginExtensions = {
      ...this.props.pluginType,
      extensions: {
        dictionaries: newExtensionList,
        corrections: this.props.pluginType.extensions.corrections,
      },
    };
    this.props.updatePlugin(this.props.pluginType.extensionKey, plugin);
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
          <SquareXButton
            customStyles={styles.packageWithExtensionCloseButton}
            onClick={this.deletePlugin}
          />
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
                style={styles.addResourceButton}>
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
                {this.props.pluginType.extensions.dictionaries &&
                  this.props.pluginType.extensions.dictionaries.map(
                    (ext, i) => (
                      <Extension
                        key={i}
                        index={i}
                        pluginType={ext}
                        deleteExtension={this.deleteExtension}
                        pluginResource={this.getResource(ext)}
                        updateExtension={this.updateExtension}
                        editDisabled={this.props.editDisabled}
                      />
                    ),
                  )}
                {this.props.pluginType.extensions.corrections &&
                  this.props.pluginType.extensions.corrections.map((ext, i) => (
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

const ComposedPluginWithExtensions: React.ComponentClass<IPublicProps> = compose<IPrivateProps, IPublicProps>(
  pure,
  setDisplayName('PluginWithExtensions'),
  Radium,
)(PluginWithExtensions);

export default ComposedPluginWithExtensions;
