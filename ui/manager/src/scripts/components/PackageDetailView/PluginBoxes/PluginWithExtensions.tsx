import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IOptions } from '../PackageView';
import Parser from '../../utils/Parser';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import { CSSProperties } from 'react';
import styles from './Plugin.styles';
import Extension from './Extension';
import { IPlugin, IPluginTypes } from '../../utils/AxiosFunctions';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { getDate } from '../../utils/DateFormat';
import { ModalEnum } from '../../utils/ModalEnum';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import * as PluginType from '../../utils/EddiTypes';
import PluginHelper from '../../utils/helpers/PluginHelper';
import * as Radium from 'radium';

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
  deletePlugin(extensionKey: number): void;
  updateExtensionsInPlugin(extensionKey: number, extensions: IOptions[]): void;
}

interface IPrivateProps extends IPublicProps {
  plugin: IPlugin;
}

class PluginWithExtensions extends React.Component<IPrivateProps> {
  componentDidMount() {
    if (this.props.pluginResource) {
      eddiApiActionDispatchers.fetchPluginAction(this.props.pluginResource);
    }
  }

  deletePlugin = () => {
    this.props.deletePlugin(this.props.pluginType.extensionKey);
  };

  deleteExtension = (extensionKey: number) => {
    const newExtensionList = this.props.pluginType.extensions
      .filter(e => e.extensionKey !== extensionKey)
      .map((ext, i) => {
        return { type: ext.type, resource: ext.resource, extensionKey: i };
      });
    this.props.updateExtensionsInPlugin(
      this.props.pluginType.extensionKey,
      newExtensionList,
    );
  };

  selectExtensions = (newPluginResourceList: string[]) => {
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
      case PluginType.BEHAVIOUR:
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
    );
  };

  openAddPluginsModal = () => {
    const extensionList = this.props.pluginType.extensions.map(p => {
      return p.resource;
    });
    const pluginType = PluginType.REGULAR_DICTIONARY;
    ModalActionDispatchers.showAddPluginsModal(
      pluginType,
      extensionList,
      this.selectExtensions,
    );
  };

  updateExtension = (extensionResource: string) => {
    const newExtensionList = this.props.pluginType.extensions.map(ext => {
      return {
        type: ext.type,
        resource:
          Parser.getId(ext.resource) === Parser.getId(extensionResource)
            ? extensionResource
            : ext.resource,
        extensionKey: ext.extensionKey,
      };
    });
    this.props.updateExtensionsInPlugin(
      this.props.pluginType.extensionKey,
      newExtensionList,
    );
  };

  render() {
    return (
      <div style={styles.pluginWithExtensionsContainer}>
        <div style={styles.closeButton} onClick={this.deletePlugin}>
          &times;
        </div>
        <div style={styles.pluginBoxWithExtensions}>
          <div style={styles.bigPluginName}>
            <div style={styles.pluginName}>
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
            <div style={styles.centerFlex} />
            <a
              onClick={this.openAddPluginsModal}
              style={styles.addExtensionButton}>
              {'Add dictionary'}
            </a>
          </div>
          <div style={styles.pluginDate}>
            {PluginHelper.getLastModified(
              this.props.pluginType,
              this.props.plugin,
              true,
              '',
            )}
          </div>
          <div style={styles.pluginDate}>{this.props.pluginType.type}</div>
          {renderIf(!_.isEmpty(this.props.pluginType.extensions))(() => (
            <div>
              <div style={customStyles.extensionList}>
                {this.props.pluginType.extensions.map((ext, i) => (
                  <Extension
                    key={i}
                    index={i}
                    pluginType={ext}
                    deleteExtension={this.deleteExtension}
                    pluginResource={ext.resource}
                    updateExtension={this.updateExtension}
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
