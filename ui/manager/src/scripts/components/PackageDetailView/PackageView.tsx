import clsx from 'clsx';
import * as _ from 'lodash';
import * as React from 'react';
import { connect, useSelector } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import { isChatOpenedSelector } from '../../selectors/ChatSelectors';
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
import useStyles from './PackageView.styles';
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

const warningIcon = require('../../../public/images/WarningIcon.png');
const foundUnpublishedChanges = false; // todo : add function to check if there are unpublished changes.

const PackageView = ({
  packagePayload,
  defaultPluginTypes: defaultPluginTypesProps,
  readOnly,
}: IPrivateProps) => {
  const [selectedPlugins, setSelectedPlugins] = React.useState<IOptions[]>([]);
  const [initialSelectedPluginState, setInitialSelectedPluginState] =
    React.useState<IOptions[]>([]);
  const [defaultPluginTypes, setDefaultPluginTypes] = React.useState<
    IDefaultPluginTypes[]
  >([]);
  const [extensionKey, setExtensionKey] = React.useState(0);

  const { isOpened: isChatOpened } = useSelector(isChatOpenedSelector);

  React.useEffect(() => {
    eddiApiActionDispatchers.fetchPackageDataAction(packagePayload.resource);
    eddiApiActionDispatchers.fetchDefaultPluginTypesAction();
    discardChanges();
  }, []);

  const classes = useStyles();

  React.useEffect(() => {
    if (!packagePayload.packageData) {
      eddiApiActionDispatchers.fetchPackageDataAction(packagePayload.resource);
    }
    if (!_.isEmpty(packagePayload.packageData)) {
      discardChanges();
    }
    setDefaultPluginTypes(defaultPluginTypesProps);
  }, [defaultPluginTypesProps, packagePayload.packageData]);

  const openEditPackageModal = () => {
    ModalActionDispatchers.showEditDescriptorModalAction(packagePayload);
  };

  const getBotIdFromQueryString = () => {
    const botId = Parser.getQueryStrings(location.search).botId;
    return botId;
  };

  const replacer = (key, value) => {
    if (key === 'extensionKey') {
      return undefined;
    } else {
      return value;
    }
  };

  const openEditJsonModal = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(PACKAGE);
    ModalActionDispatchers.showEditJsonModal(
      packagePayload.resource,
      JSON.stringify({ packageExtensions: selectedPlugins }, replacer, '\t'),
    );
  };

  const discardChanges = () => {
    if (_.isUndefined(packagePayload.packageData)) {
      return;
    }
    const initialSelectedPluginState =
      packagePayload.packageData.packageExtensions.map((o, i) => ({
        ...o,
        extensionKey: i,
      }));
    if (!_.isUndefined(packagePayload.packageData)) {
      setSelectedPlugins(initialSelectedPluginState);
      setInitialSelectedPluginState(initialSelectedPluginState);
      setExtensionKey(packagePayload.packageData.packageExtensions.length);
    }
  };

  const addPlugin = (addedExtension: IPluginExtensions) => {
    const newPluginList = selectedPlugins.map((plugin, i) => ({
      ...plugin,
      extensionKey: i,
    }));
    newPluginList.push({
      ...addedExtension,
      extensionKey: extensionKey,
    });
    setSelectedPlugins(newPluginList);
    setExtensionKey(newPluginList.length);
  };

  const saveChanges = async (deploy: boolean = false) => {
    const list = selectedPlugins.map((selected) => {
      return { ...selected };
    });
    eddiApiActionDispatchers.updateJsonDataAction(packagePayload.resource, {
      packageExtensions: list,
      botId: getBotIdFromQueryString(),
      deploy,
    });
  };

  const deletePlugin = (extensionKey: number) => {
    const newPluginList = selectedPlugins
      .filter((plugin) => plugin.extensionKey !== extensionKey)
      .map((plugin, i) => ({
        type: plugin.type,
        extensions: plugin.extensions,
        config: plugin.config,
        extensionKey: i,
      }));
    setSelectedPlugins(newPluginList);
    setExtensionKey(newPluginList.length);
  };

  const updatePlugin = (pluginKey: number, newPlugin: IOptions) => {
    const newPluginList = selectedPlugins.map((plugin) => {
      if (plugin.extensionKey === pluginKey) {
        return { ...newPlugin };
      } else {
        return { ...plugin };
      }
    });
    setSelectedPlugins(newPluginList);
    setExtensionKey(newPluginList.length);
  };

  const unsavedChanges = () => {
    return !_.isEqual(
      JSON.stringify(selectedPlugins),
      JSON.stringify(initialSelectedPluginState),
    );
  };

  const selectVersion = (newVersion: number) => {
    eddiApiActionDispatchers.fetchPackageAction(
      Parser.replaceResourceVersion(packagePayload.resource, newVersion),
    );
    if (newVersion === packagePayload.currentVersion) {
      historyPush(`/packageview/${packagePayload.id}`);
    } else {
      historyPush(`/packageview/${packagePayload.id}`, [
        `version=${newVersion}`,
      ]);
    }
  };

  const isCurrentVersion =
    packagePayload.version === packagePayload.currentVersion;

  const openParallelConfigModal = () => {
    ModalActionDispatchers.showParallelConfigModal(packagePayload);
  };

  return (
    <div>
      <div className={classes.packageHeader}>
        <div className={classes.packageName}>
          {packagePayload.name || packagePayload.id}
        </div>
        <VersionSelectComponent
          selectedVersion={packagePayload.version}
          currentVersion={packagePayload.currentVersion}
          selectVersion={selectVersion}
        />
        <WhiteButton
          onClick={openEditPackageModal}
          text={'Rename'}
          classes={{ button: classes.editPackageButton }}
          disabled={!isCurrentVersion || readOnly}
        />
        <WhiteButton
          onClick={openParallelConfigModal}
          text={'Edit JSON'}
          classes={{ button: classes.editPackageButton }}
          disabled={!isCurrentVersion || readOnly}
        />
        {foundUnpublishedChanges && (
          <div className={classes.unpublishedChanges}>
            <img src={warningIcon} className={classes.warningIcon} />
            <div className={classes.unpublishedChangesText}>
              {'This Package has unpublished changes'}
            </div>
          </div>
        )}
        <div className={classes.packageHeaderSpacing} />
        {unsavedChanges() && (
          <button
            className={classes.discardChanges}
            onClick={() => discardChanges()}>
            {'Discard changes'}
          </button>
        )}
        <div className={classes.options} onClick={(e) => e.stopPropagation()}>
          <Options
            descriptor={packagePayload}
            data={JSON.stringify(packagePayload.packageData, null, '\t')}
          />
        </div>
        <BlueButton
          text={'Save'}
          disabled={!unsavedChanges()}
          onClick={() => saveChanges()}
        />
        <BlueButton
          text={'Save & test'}
          disabled={!unsavedChanges()}
          onClick={() => saveChanges(true)}
          classes={{ button: classes.greenButton }}
        />
      </div>
      <PackageDescriptor packagePayload={packagePayload} />
      <div className={classes.usedInBotsTitle}>
        {'Used in bots'}
        <BotsUsingPackage packagePayload={packagePayload} />
      </div>
      {!!selectedPlugins && !_.isEmpty(selectedPlugins) && (
        <div>
          {selectedPlugins
            .filter((p) => hasExtensions(p))
            .map((ext, key) => (
              <PluginWithExtension
                key={key}
                pluginType={ext}
                pluginResource={PluginHelper.getResource(ext)}
                deletePlugin={deletePlugin}
                updatePlugin={updatePlugin}
                openParallelConfigModal={openParallelConfigModal}
                editDisabled={!isCurrentVersion || readOnly}
              />
            ))}
          <div
            className={clsx(
              classes.pluginList,
              isChatOpened ? classes.pluginListColumn : null,
            )}>
            {selectedPlugins
              .filter((p) => !hasExtensions(p))
              .map((ext, key) => (
                <Plugin
                  key={key}
                  pluginType={ext}
                  deletePlugin={deletePlugin}
                  pluginResource={PluginHelper.getResource(ext)}
                  updatePlugin={updatePlugin}
                  openParallelConfigModal={openParallelConfigModal}
                  editDisabled={!isCurrentVersion || readOnly}
                />
              ))}
          </div>
        </div>
      )}
      {isCurrentVersion && !readOnly && (
        <div>
          <div className={classes.pluginAddTitle}>{'Add plugins'}</div>
          <div className={classes.pluginDropdown}>
            <PluginSelect
              packageExtensions={defaultPluginTypes.map((extension) => {
                return extension;
              })}
              addExtension={addPlugin}
            />
          </div>
        </div>
      )}
    </div>
  );
};

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
