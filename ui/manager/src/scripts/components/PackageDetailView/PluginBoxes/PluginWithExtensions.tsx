import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import SquareXButton from '../../Assets/Buttons/SquareXButton';
import { IPlugin, IPluginExtensions } from '../../utils/AxiosFunctions';
import * as PluginType from '../../utils/EddiTypes';
import {
  CORRECTION,
  DICTIONARY,
  REGULAR_DICTIONARY,
} from '../../utils/EddiTypes';
import PluginHelper from '../../utils/helpers/PluginHelper';
import Parser from '../../utils/Parser';
import { IOptions } from '../PackageView';
import Extension from './Extension';
import useStyles from './Plugin.styles';

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

const PluginWithExtensions = ({
  pluginType,
  pluginResource,
  editDisabled,
  deletePlugin,
  updatePlugin,
  plugin,
}: IPrivateProps) => {
  const classes = useStyles();

  React.useEffect(() => {
    if (pluginResource) {
      eddiApiActionDispatchers.fetchPluginAction(pluginResource);
    }
    let dictionaries: IOptions[] = [];
    let corrections: IOptions[] = [];
    if (!_.isEmpty(pluginType.extensions)) {
      if (!_.isEmpty(pluginType.extensions.dictionaries)) {
        dictionaries = pluginType.extensions.dictionaries.map(
          (dictionary, i) => ({
            ...dictionary,
            extensionKey: i,
          }),
        );
      }
      if (!_.isEmpty(pluginType.extensions.corrections)) {
        corrections = pluginType.extensions.corrections.map(
          (correction, i) => ({
            ...correction,
            extensionKey: i,
          }),
        );
      }
    }
  }, []);

  const handleDeletePlugin = () => {
    deletePlugin(pluginType.extensionKey);
  };

  const deleteExtension = (extensionKey: number, type: string) => {
    let dictionaries = [];
    let corrections = [];
    if (type.includes(CORRECTION)) {
      dictionaries = pluginType.extensions.dictionaries;
      corrections = pluginType.extensions.corrrections.splice(
        pluginType.extensions.corrections.findIndex(
          (c, i) => i === extensionKey,
        ),
        1,
      );
    } else if (type.includes(DICTIONARY)) {
      dictionaries = pluginType.extensions.dictionaries.splice(
        pluginType.extensions.dictionaries.findIndex(
          (d, i) => i === extensionKey,
        ),
        1,
      );
      corrections = pluginType.extensions.corrections;
    }
    const plugin: IPluginExtensions = {
      ...pluginType,
      extensions: {
        dictionaries,
        corrections,
      },
    };
    updatePlugin(pluginType.extensionKey, plugin);
  };

  const updatePluginResource = (newPluginResourceList: string[]) => {
    if (pluginType.type === PluginType.PARSER) {
      const otherDictionaries = pluginType.extensions.dictionaries.filter(
        (d) => d.type !== REGULAR_DICTIONARY,
      );
      const newRegularDictionaryList = newPluginResourceList.map((resource) => {
        return { type: REGULAR_DICTIONARY, config: { uri: resource } };
      });
      const newPlugin: IPluginExtensions = {
        ...pluginType,
        extensions: {
          dictionaries: otherDictionaries.concat(newRegularDictionaryList),
          corrections: pluginType.extensions.corrections,
        },
      };
      updatePlugin(pluginType.extensionKey, newPlugin);
    } else if (!_.isEmpty(newPluginResourceList)) {
      const plugin: IPluginExtensions = {
        ...pluginType,
        config: {
          ...pluginType.config,
          uri: newPluginResourceList[0],
        },
      };
      updatePlugin(pluginType.extensionKey, plugin);
    }
  };

  const openAddPluginsModal = () => {
    let extensionList: string[];
    let tempPluginType;
    if (pluginType.type === PluginType.PARSER) {
      if (pluginType.config && !_.isEmpty(pluginType.extensions.dictionaries)) {
        extensionList = pluginType.extensions.dictionaries
          .filter((d) => d.config && d.config.uri)
          .map((resource) => {
            return resource.config.uri;
          });
      }
      tempPluginType = PluginType.REGULAR_DICTIONARY;
    } else {
      tempPluginType = pluginType.type;
      extensionList = (pluginType.config.uri && [pluginType.config.uri]) || [];
    }
    ModalActionDispatchers.showAddPluginsModal(
      tempPluginType,
      extensionList,
      updatePluginResource,
    );
  };

  const updateExtension = (extensionResource: string) => {
    const newExtensionList = pluginType.extensions.dictionaries.map((ext) => {
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
    });
    const plugin: IPluginExtensions = {
      ...pluginType,
      extensions: {
        dictionaries: newExtensionList,
        corrections: pluginType.extensions.corrections,
      },
    };
    updatePlugin(pluginType.extensionKey, plugin);
  };

  const getResource = (plugin: IPluginExtensions) => {
    if (plugin.config) {
      return plugin.config.uri;
    }
    return null;
  };

  return (
    <div className={classes.pluginWithExtensionsContainer}>
      {!editDisabled && (
        <SquareXButton
          classes={{ button: classes.packageWithExtensionCloseButton }}
          onClick={handleDeletePlugin}
        />
      )}
      <div className={classes.pluginBoxWithExtensions}>
        <div className={classes.bigPluginName}>
          <div className={classes.pluginName}>
            {PluginHelper.getName(pluginType.type, plugin, true)}
          </div>
          <div className={classes.pluginVersion}>
            {PluginHelper.getVersion(pluginType.type, plugin, true)}
          </div>
          <div className={classes.centerFlex} />
          {!editDisabled && (
            <a
              onClick={openAddPluginsModal}
              className={classes.addResourceButton}>
              {'Add dictionary'}
            </a>
          )}
        </div>
        <div className={classes.pluginDate}>
          {PluginHelper.getLastModified(pluginType.type, plugin, true, '')}
        </div>
        <div className={classes.pluginDate}>{pluginType.type}</div>
        {!_.isEmpty(pluginType.extensions) && (
          <div>
            <div className={classes.extensionList}>
              {pluginType.extensions.dictionaries &&
                pluginType.extensions.dictionaries.map((ext, i) => (
                  <Extension
                    key={i}
                    index={i}
                    pluginType={ext}
                    deleteExtension={deleteExtension}
                    pluginResource={getResource(ext)}
                    updateExtension={updateExtension}
                    editDisabled={editDisabled}
                  />
                ))}
              {pluginType.extensions.corrections &&
                pluginType.extensions.corrections.map((ext, i) => (
                  <Extension
                    key={i}
                    index={i}
                    pluginType={ext}
                    deleteExtension={deleteExtension}
                    pluginResource={getResource(ext)}
                    updateExtension={updateExtension}
                    editDisabled={editDisabled}
                  />
                ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

const ComposedPluginWithExtensions: React.ComponentClass<IPublicProps> =
  compose<IPrivateProps, IPublicProps>(
    pure,
    setDisplayName('PluginWithExtensions'),
  )(PluginWithExtensions);

export default ComposedPluginWithExtensions;
