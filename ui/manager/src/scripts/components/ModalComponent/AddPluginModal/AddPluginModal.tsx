import * as _ from 'lodash';
import * as React from 'react';
import { useSelector } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { pluginsSelector } from '../../../selectors/PluginSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';
import { REGULAR_DICTIONARY } from '../../utils/EddiTypes';
import Parser from '../../utils/Parser';
import useStyles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import PluginsList from './PluginsList';

interface IPublicProps {
  pluginType: string;
  oldPlugins: string[];
  addPlugins(selectedPlugins: string[]): void;
}

interface IPrivateProps extends IPublicProps {}

const AddPluginModal = (props: IPrivateProps) => {
  const { isLoading, plugins, isAllPluginsLoaded, loadedPlugins } = useSelector(
    (state) => pluginsSelector(state, props.pluginType),
  );
  const [selectedPlugins, setSelectedPlugins] = React.useState<string[]>([]);
  const [availablePlugins, setAvailablePlugins] = React.useState<string[]>([]);
  const [limitedToOneSelect, setLimitedToOneSelect] = React.useState(true);
  const [loading, setLoading] = React.useState(false);
  const classes = useStyles();

  React.useEffect(() => {
    if (props.pluginType === REGULAR_DICTIONARY) {
      setLimitedToOneSelect(false);
    }
    if (plugins.length < DEFAULT_LIMIT && !isAllPluginsLoaded) {
      eddiApiActionDispatchers.fetchPluginsAction(
        props.pluginType,
        DEFAULT_LIMIT,
        0,
      );
    }
    discardChanges();
  }, []);

  const prevPluginsRef = React.useRef(null);

  React.useEffect(() => {
    if (
      !_.isEmpty(_.differenceBy(plugins, prevPluginsRef.current, 'resource'))
    ) {
      discardChanges();
    }
  }, [plugins]);

  React.useEffect(() => {
    prevPluginsRef.current = plugins;
  });

  const closeModal = () => {
    discardChanges();
    ModalActionDispatchers.closeModal();
  };

  const selectVersion = (resource: string, version: number) => {
    const id = Parser.getId(resource);
    const tempAvailablePlugins = availablePlugins.map((p) => {
      if (Parser.getId(p) === id) {
        return Parser.replaceResourceVersion(p, version);
      }
      return p;
    });
    const tempSelectedPlugins = selectedPlugins.filter(
      (selectedPackage) => Parser.getId(selectedPackage) !== id,
    );
    setAvailablePlugins(tempAvailablePlugins);
    setSelectedPlugins(tempSelectedPlugins);
  };

  const selectPlugin = (pluginResource: string) => {
    if (limitedToOneSelect) {
      if (_.first(selectedPlugins) === pluginResource) {
        setSelectedPlugins([]);
      } else {
        setSelectedPlugins([pluginResource]);
      }
    } else {
      if (selectedPlugins.includes(pluginResource)) {
        setSelectedPlugins(selectedPlugins.filter((p) => p !== pluginResource));
      } else {
        setSelectedPlugins(selectedPlugins.concat(pluginResource));
      }
    }
  };

  const unsavedChanges = (): boolean => {
    return !_.isEqual(selectedPlugins.sort(), props.oldPlugins.sort());
  };

  const discardChanges = (): void => {
    const availablePlugins = plugins.map((pkg) => {
      return getPluginIfUsed(pkg.resource);
    });
    setSelectedPlugins(props.oldPlugins);
    setAvailablePlugins(availablePlugins);
  };

  const isPluginSelected = (pluginResource: string): boolean => {
    return !!selectedPlugins.find(
      (selectedPlugin) =>
        Parser.getId(pluginResource) === Parser.getId(selectedPlugin),
    );
  };

  const getPluginIfUsed = (pluginResource: string): string => {
    const plugin = props.oldPlugins.find(
      (p) => Parser.getId(pluginResource) === Parser.getId(p),
    );
    return plugin || pluginResource;
  };

  const selectPlugins = () => {
    props.addPlugins(selectedPlugins);
    closeModal();
  };

  const createNewPlugin = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(props.pluginType);
    ModalActionDispatchers.showCreateNewConfigModal(
      props.pluginType,
      null,
      null,
      null,
      () =>
        ModalActionDispatchers.showAddPluginsModal(
          props.pluginType,
          props.oldPlugins,
          props.addPlugins,
        ),
    );
  };

  const loadMore = () => {
    const fetchIndex = Math.floor(loadedPlugins / DEFAULT_LIMIT);
    if (loading || _.isEmpty(plugins)) {
      return;
    }
    setLoading(true);
    eddiApiActionDispatchers.fetchPluginsAction(
      props.pluginType,
      DEFAULT_LIMIT,
      fetchIndex,
    );
  };

  return (
    <div>
      <div className={classes.header}>
        <div className={classes.topHeader}>
          <div className={classes.title}>{`Select ${Parser.getPluginName(
            props.pluginType,
            true,
          )}`}</div>
          <div className={classes.centerFlex} />
          <WhiteButton
            classes={{ button: classes.createButton }}
            onClick={createNewPlugin}
            text={`Create new ${Parser.getPluginName(props.pluginType, false)}`}
          />
          <BlueButton
            classes={{ button: classes.button }}
            disabled={!unsavedChanges() || _.isEmpty(selectedPlugins)}
            onClick={selectPlugins}
            text={`Add ${Parser.getPluginName(props.pluginType, false)}`}
          />
        </div>
        <div className={classes.bottomHeader}>
          <div className={classes.centerFlex} />
          <div className={classes.lastModified}>{'Last modified'}</div>
        </div>
      </div>
      <div className={classes.packageList}>
        {isAllPluginsLoaded && _.isEmpty(plugins) && (
          <p>
            {'Found no plugins. Create a new ' +
              Parser.getPluginName(props.pluginType, false) +
              ' to select one.'}
          </p>
        )}
        <PluginsList
          availablePlugins={availablePlugins}
          isPluginSelected={isPluginSelected}
          selectPlugin={selectPlugin}
          selectVersion={selectVersion}
        />
        {isLoading && (
          <div className={classes.loadingWrapper}>
            <ClimbingBoxLoader loading color="white" />
          </div>
        )}
        {!isAllPluginsLoaded && !isLoading && !loading && (
          <BlueButton
            classes={{ button: classes.loadMoreButton }}
            onClick={loadMore}
            text={'Load More'}
          />
        )}
      </div>
    </div>
  );
};
const ComposedAddPluginModal: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('AddPluginModal'),
)(AddPluginModal);

export default ComposedAddPluginModal;
