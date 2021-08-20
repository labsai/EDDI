import * as _ from 'lodash';
import * as moment from 'moment';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import {
  BLUE_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import TruncateTextComponent from '../../Assets/TruncateTextComponent';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import PackagesUsingPlugin from '../../PackageDetailView/UsedByComponent/PackagesUsingPlugin';
import { IPlugin } from '../../utils/AxiosFunctions';
import useStyles from '../AddPackagesModal/Package.styles';

interface IPublicProps {
  pluginResource: string;
  selected: boolean;
  filterText?: string;
  handleClick(resource: string): void;
  selectVersion(resource: string, newVersion): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  plugin: IPlugin;
  isLoading: boolean;
}

const Plugin = (props: IPrivateProps) => {
  const classes = useStyles();
  React.useEffect(() => {
    if (
      props.pluginResource &&
      (_.isUndefined(props.plugin) || _.isEmpty(props.plugin))
    ) {
      eddiApiActionDispatchers.fetchPluginAction(props.pluginResource);
    }
  }, [props.pluginResource]);

  const handleClick = () => {
    props.handleClick(props.plugin.resource);
  };

  const selectVersion = (newVersion: number) => {
    props.selectVersion(props.plugin.resource, newVersion);
  };

  const filterOut =
    props.filterText &&
    !props.plugin.name.toLowerCase().includes(props.filterText.toLowerCase()) &&
    !props.plugin.description
      .toLowerCase()
      .includes(props.filterText.toLowerCase());

  return (
    <div>
      {!props.plugin && (
        <div>
          {props.isLoading && <p>{'Loading plugin'}</p>}
          {!!props.error && <p>{'Error: Could not load plugin'}</p>}
          {!props.isLoading && !props.error && (
            <p>{'This plugin does not exist'}</p>
          )}
        </div>
      )}
      {!!props.plugin && (
        <div>
          {!!props.error && <p>{'Error: Could not load plugin'}</p>}
          {props.isLoading && (
            <p className={classes.loading}>{'Loading plugin'}</p>
          )}
          {!props.error && _.isEmpty(props.plugin) && !props.isLoading && (
            <p>{'This plugin does not exist'}</p>
          )}
          {!props.error && !_.isEmpty(props.plugin) && !filterOut && (
            <div className={classes.content}>
              <div className={classes.topContent}>
                <button
                  onClick={handleClick}
                  style={{
                    backgroundColor: props.selected ? '#4BCA81' : undefined,
                  }}
                  className={classes.button}>{`${
                  props.selected ? '\u2714' : '+'
                }`}</button>
                <div
                  style={{
                    color: props.selected ? BLUE_COLOR : undefined,
                  }}
                  className={classes.packageName}>
                  {props.plugin.name === ''
                    ? props.plugin.id
                    : props.plugin.name}
                </div>
                <div className={classes.versionSelect}>
                  <VersionSelectComponent
                    currentVersion={props.plugin.currentVersion}
                    selectedVersion={props.plugin.version}
                    selectVersion={selectVersion}
                    classes={{ input: classes.selectInput }}
                  />
                </div>
                <div className={classes.centerFlex} />
                <div className={classes.modifiedDate}>
                  {moment(props.plugin.lastModifiedOn).format('DD.MM.YYYY')}
                </div>
              </div>
              <div className={classes.bottomContent}>
                <TruncateTextComponent
                  text={props.plugin.description}
                  length={80}
                />
                <PackagesUsingPlugin plugin={props.plugin} isSmallName={true} />
              </div>
            </div>
          )}
        </div>
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
