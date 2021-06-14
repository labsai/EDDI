import * as _ from 'lodash';
import * as React from 'react';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { pluginsSelector } from '../../../selectors/PluginSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import WhiteButton from '../../Assets/Buttons/WhiteButton';
import { DEFAULT_LIMIT } from '../../utils/ApiFunctions';
import { IDescriptor } from '../../utils/AxiosFunctions';
import { REGULAR_DICTIONARY } from '../../utils/EddiTypes';
import Parser from '../../utils/Parser';
import useStyles from '../AddPackagesModal/AddPackagesModal.styles';
import '../ModalComponent.styles.scss';
import Plugin from './Plugin';

interface IPublicProps {
  pluginType: string;
  oldPlugins: string[];
  addPlugins(selectedPlugins: string[]): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  isLoading: boolean;
  plugins: IDescriptor[];
  isAllPluginsLoaded: boolean;
  loadedPlugins: number;
}

const AddPluginModal = (props: IPrivateProps) => {
  const [selectedPlugins, setSelectedPlugins] = React.useState<string[]>([]);
  const [availablePlugins, setAvailablePlugins] = React.useState<string[]>([]);
  const [limitedToOneSelect, setLimitedToOneSelect] = React.useState(true);
  const [loading, setLoading] = React.useState(false);
  const classes = useStyles();

  React.useEffect(() => {
    if (props.pluginType === REGULAR_DICTIONARY) {
      setLimitedToOneSelect(false);
    }
    if (props.plugins.length < DEFAULT_LIMIT && !props.isAllPluginsLoaded) {
      eddiApiActionDispatchers.fetchPluginsAction(
        props.pluginType,
        DEFAULT_LIMIT,
        0,
      );
    }
    discardChanges();
  }, []);

  React.useEffect(() => {
    discardChanges();
  }, [props.plugins, props.pluginType]);

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
    const availablePlugins = props.plugins.map((pkg) => {
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
    const fetchIndex = Math.floor(props.loadedPlugins / DEFAULT_LIMIT);
    if (loading || _.isEmpty(props.plugins)) {
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
        {props.isAllPluginsLoaded && _.isEmpty(props.plugins) && (
          <p>
            {'Found no plugins. Create a new ' +
              Parser.getPluginName(props.pluginType, false) +
              ' to select one.'}
          </p>
        )}
        {!_.isEmpty(availablePlugins) && (
          <div>
            {availablePlugins.map((p, i) => (
              <Plugin
                key={i}
                selected={isPluginSelected(p)}
                pluginResource={p}
                handleClick={selectPlugin}
                selectVersion={selectVersion}
              />
            ))}
          </div>
        )}
        {props.isLoading && (
          <div className={classes.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        )}
        {!props.isAllPluginsLoaded && !props.isLoading && !loading && (
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
  connect(pluginsSelector),
)(AddPluginModal);

export default ComposedAddPluginModal;
