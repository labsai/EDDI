import clsx from 'clsx';
import * as _ from 'lodash';
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
import useStyles from './Plugin.styles';

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

const Plugin = ({
  pluginResource,
  pluginType,
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
  }, [pluginResource]);

  const handleDeletePlugin = () => {
    deletePlugin(pluginType.extensionKey);
  };

  const openAddPluginsModal = (e) => {
    e.stopPropagation();
    let extensionList: string[] = [];
    let pluginType;
    if (pluginType.type === PluginType.PARSER) {
      if (pluginType.config && !_.isEmpty(pluginType.extensions.dictionaries)) {
        extensionList = pluginType.extensions.dictionaries.map((p) => {
          return p.config.uri;
        });
      }
      pluginType = PluginType.REGULAR_DICTIONARY;
    } else {
      pluginType = pluginType.type;
      extensionList = (pluginResource && [pluginResource]) || [];
    }
    ModalActionDispatchers.showAddPluginsModal(
      pluginType,
      extensionList,
      updatePluginResource,
    );
  };

  const updatePluginResource = (newPluginResourceList: string[]) => {
    if (pluginType.type === PluginType.PARSER) {
      let otherDictionaries = [];
      if (
        !_.isEmpty(pluginType.extensions) &&
        !_.isEmpty(pluginType.extensions.dictionaries)
      ) {
        otherDictionaries = pluginType.extensions.dictionaries.filter(
          (d) => d.type !== REGULAR_DICTIONARY,
        );
      }
      const newRegularDictionaryList = newPluginResourceList.map((resource) => {
        return { type: REGULAR_DICTIONARY, config: { uri: resource } };
      });
      const newPlugin: IPluginExtensions = {
        ...pluginType,
        extensions: {
          dictionaries: otherDictionaries.concat(newRegularDictionaryList),
          corrections:
            !_.isEmpty(pluginType.extensions) &&
            !_.isEmpty(pluginType.extensions.corrections)
              ? pluginType.extensions.corrections
              : null,
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

  const updateVersion = () => {
    const newPlugin: IPluginExtensions = {
      ...pluginType,
      config: {
        ...pluginType.config,
        uri: Parser.replaceResourceVersion(
          plugin.resource,
          plugin.currentVersion,
        ),
      },
    };
    updatePlugin(pluginType.extensionKey, newPlugin);
  };

  const getButtonName = (type: string) => {
    if (type === PluginType.PARSER) {
      return 'Add dictionary';
    } else {
      return `Add ${Parser.getPluginName(type, false)}`;
    }
  };

  const openViewJsonModal = () => {
    if (!_.isEmpty(pluginResource)) {
      ModalActionDispatchers.showViewJsonModal(pluginResource);
    }
  };

  const isCurrentVersion: boolean =
    plugin && plugin.version === plugin.currentVersion;
  let pluginCurrentVersion = 'v01';
  if (!isCurrentVersion) {
    pluginCurrentVersion = Parser.getVersionString(plugin.currentVersion);
  }
  return (
    <div className={classes.pluginContainer}>
      {!editDisabled && (
        <SquareXButton
          classes={{ button: classes.closeButton }}
          onClick={handleDeletePlugin}
        />
      )}
      <button
        className={clsx(classes.pluginBox, {
          [classes.clickablePluginBox]:
            plugin.version === plugin.currentVersion && !_.isEmpty(plugin),
          [classes.updateAvailableBorderColor]:
            plugin.version !== plugin.currentVersion,
        })}
        onClick={openViewJsonModal}>
        <div className={classes.pluginHeader}>
          <div
            key={'pluginBox'}
            className={clsx(classes.pluginName, {
              [classes.updateAvailableTextColor]:
                plugin.version !== plugin.currentVersion,
            })}>
            {PluginHelper.getName(pluginType.type, plugin, true)}
          </div>
          <div className={classes.pluginVersion}>
            {PluginHelper.getVersion(pluginType.type, plugin, true)}
          </div>
          {!editDisabled && (
            <div
              className={classes.addResourceButton}
              key={'addResource'}
              onClick={openAddPluginsModal}>
              {getButtonName(pluginType.type)}
            </div>
          )}
        </div>
        <div className={classes.pluginDate}>{pluginType.type}</div>
        <div className={classes.pluginDate}>
          {PluginHelper.getLastModified(pluginType.type, plugin, true, <br />)}
        </div>
      </button>
      {!isCurrentVersion && !editDisabled && (
        <WhiteButton
          onClick={updateVersion}
          text={`Update to ${pluginCurrentVersion}`}
          classes={{ button: classes.updateAvailableButton }}
        />
      )}
    </div>
  );
};

const ComposedPlugin: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(pluginSelector),
  setDisplayName('Plugin'),
)(Plugin);

export default ComposedPlugin;
