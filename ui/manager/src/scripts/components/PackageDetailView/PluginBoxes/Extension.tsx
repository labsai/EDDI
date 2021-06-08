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
import PluginHelper from '../../utils/helpers/PluginHelper';
import Parser from '../../utils/Parser';
import useStyles from './Plugin.styles';

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

const Extension = ({
  pluginType,
  index,
  pluginResource,
  editDisabled,
  deleteExtension,
  updateExtension,
  plugin,
}: IPrivateProps) => {
  const classes = useStyles();
  React.useEffect(() => {
    if (!_.isEmpty(pluginResource)) {
      eddiApiActionDispatchers.fetchPluginAction(pluginResource);
    }
  }, [pluginResource]);

  const handleDeleteExtension = () => {
    deleteExtension(index, pluginType.type);
  };

  const updateVersion = () => {
    updateExtension(
      Parser.replaceResourceVersion(plugin.resource, plugin.currentVersion),
    );
  };

  const getPluginName = () => {
    if (!_.isEmpty(plugin)) {
      return (
        (plugin && plugin.name) || Parser.getPluginName(pluginType.type, true)
      );
    } else {
      return Parser.getPluginName(pluginType.type, true);
    }
  };

  const openViewJsonModal = () => {
    if (!_.isEmpty(pluginResource)) {
      ModalActionDispatchers.showViewJsonModal(pluginResource);
    }
  };

  const isCurrentVersion: boolean =
    plugin && plugin.version === plugin.currentVersion;
  let pluginLatestVersion = 'v01';
  if (!isCurrentVersion) {
    pluginLatestVersion = Parser.getVersionString(plugin.currentVersion);
  }
  return (
    <div className={classes.extensionContainer}>
      {!editDisabled && (
        <SquareXButton
          classes={{ button: classes.closeButton }}
          onClick={handleDeleteExtension}
        />
      )}
      <button
        onClick={openViewJsonModal}
        className={clsx(classes.extensionBox, {
          [classes.clickablePluginBox]:
            plugin.version === plugin.currentVersion && !_.isEmpty(plugin),
          [classes.updateAvailableBorderColor]:
            plugin.version !== plugin.currentVersion,
        })}>
        <div className={classes.pluginHeader}>
          <div
            className={clsx(classes.pluginName, {
              [classes.updateAvailableTextColor]:
                plugin.version !== plugin.currentVersion,
            })}>
            {getPluginName()}
          </div>
          <div className={classes.pluginVersion}>
            {PluginHelper.getVersion(pluginType.type, plugin, true)}
          </div>
        </div>
        <div className={classes.pluginDate}>
          {Parser.getExtensionType(pluginType.type)}
        </div>
        <div className={classes.pluginDate}>
          {PluginHelper.getLastModified(pluginType.type, plugin, true, <br />)}
        </div>
      </button>
      {!isCurrentVersion && !editDisabled && (
        <WhiteButton
          onClick={updateVersion}
          text={`Update to ${pluginLatestVersion}`}
          classes={{ butoon: classes.updateAvailableButton }}
        />
      )}
    </div>
  );
};

const ComposedExtension: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(pluginSelector),
  setDisplayName('Extension'),
)(Extension);

export default ComposedExtension;
