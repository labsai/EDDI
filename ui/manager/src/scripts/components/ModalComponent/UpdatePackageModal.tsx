import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { defaultPluginTypesSelector } from '../../selectors/PluginSelectors';
import PluginSelect from '../PackageDetailView/DropDownComponents/PluginSelect';
import { IOptions } from '../PackageDetailView/PackageView';
import Plugin from '../PackageDetailView/PluginBoxes/Plugin';
import { createNewPackage, IDefaultPluginTypes } from '../utils/AxiosFunctions';
import useStyles from './ModalComponent.styles';
import './ModalComponent.styles.scss';
import clsx from 'clsx';

interface IPublicProps {
  packageName: string;
  packageDescription: string;
}
interface IPrivateProps extends IPublicProps {
  defaultPluginTypes: IDefaultPluginTypes[];
}

const UpdatePackageModal = (props: IPrivateProps) => {
  const classes = useStyles();

  const [addedPlugins, setAddedPlugins] = React.useState<IOptions[]>([]);
  const [extensionKey, setExtensionKey] = React.useState<number>(0);

  React.useEffect(() => {
    eddiApiActionDispatchers.fetchDefaultPluginTypesAction();
  }, []);

  const getButtonStyle = () => {
    if (!props.packageName) {
      return {
        backgroundColor: '#c4c9d2',
      };
    } else {
      return {
        backgroundColor: '#0070D2',
      };
    }
  };

  const handleCreateNewPackage = async () => {
    const list = addedPlugins.map((a) => ({
      type: a.type,
    }));
    const packageID = await createNewPackage(
      props.packageName,
      props.packageDescription,
      list,
    );
    modalActionDispatchers.closeModal();
    eddiApiActionDispatchers.createNewPackageAction(packageID);
    historyPush(`/packageview/${packageID}`);
  };

  const addPluginsInModal = (addedPlugin: IOptions) => {
    const plugins = addedPlugins.concat({
      ...addedPlugin,
      extensionKey: extensionKey,
    });
    setAddedPlugins(plugins);
    setExtensionKey(extensionKey + 1);
  };

  const deletePlugin = (extensionKey: number) => {
    setAddedPlugins(
      addedPlugins.filter((ext) => !_.isEqual(ext.extensionKey, extensionKey)),
    );
  };

  return (
    <div>
      <div className={classes.tallModalHeader}>
        <div className={classes.modalTopHeader}>
          <div className={classes.headerTextUpdate}> {props.packageName}</div>
          <div className={classes.modalTopHeaderCenter} />
          <button
            style={getButtonStyle()}
            className={clsx(
              classes.createNewBotButton,
              classes.updatePackageCreateNewBotButton,
            )}
            onClick={handleCreateNewPackage}>
            {'Save'}
          </button>
        </div>
        <div className={classes.modalBottomHeader}>
          <div className={classes.descriptionHeaderText}>
            <div className={classes.descriptorsUpdate}>
              {props.packageDescription}
            </div>
          </div>
        </div>
      </div>
      <div className={classes.updateModalContent}>
        {!!addedPlugins && (
          <div className={classes.pluginList}>
            {addedPlugins.map((extension, key) => (
              <Plugin
                key={key}
                pluginType={extension}
                editDisabled={true}
                deletePlugin={deletePlugin}
              />
            ))}
          </div>
        )}
        <div className={classes.pluginText}>
          {'Add plugins'}
          <div className={classes.pluginSelector}>
            <PluginSelect
              packageExtensions={props.defaultPluginTypes.map((plugin) => {
                return plugin;
              })}
              addExtension={addPluginsInModal}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

const ComposedUpdatePackageModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('Modal'),
  connect(defaultPluginTypesSelector),
)(UpdatePackageModal);

export default ComposedUpdatePackageModal;
