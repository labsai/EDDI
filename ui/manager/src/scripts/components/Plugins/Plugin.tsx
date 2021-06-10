import * as _ from 'lodash';
import * as moment from 'moment';
import * as React from 'react';
import { connect } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import { pluginSelector } from '../../selectors/PluginSelectors';
import Options from '../Assets/Buttons/Options';
import TruncateTextComponent from '../Assets/TruncateTextComponent';
import VersionSelectComponent from '../Assets/VersionSelectComponent';
import PackagesUsingPlugin from '../PackageDetailView/UsedByComponent/PackagesUsingPlugin';
import { IPlugin } from '../utils/AxiosFunctions';
import useStyles from './Plugin.styles';

interface IPublicProps {
  pluginResource: string;
  selectVersion(newVersion): void;
}

interface IPrivateProps extends IPublicProps {
  error: Error;
  plugin: IPlugin;
  isLoading: boolean;
}

const Plugin: React.StatelessComponent<IPrivateProps> = (
  props: IPrivateProps,
) => {
  const classes = useStyles();
  return (
    <div className={classes.content}>
      {!props.error && _.isEmpty(props.plugin) && (
        <ClipLoader color={BLUE_COLOR} />
      )}
      {!!props.plugin && (
        <div>
          {!!props.error ? (
            <p>{'Error: Could not load plugin'}</p>
          ) : (
            <div>
              <div
                className={classes.topContent}
                onClick={(e) => {
                  e.stopPropagation();
                  modalActionDispatchers.showViewJsonModal(
                    props.plugin.resource,
                  );
                }}>
                <div className={classes.pluginName}>
                  {props.plugin.name === ''
                    ? props.plugin.id
                    : props.plugin.name}
                </div>
                <div
                  className={classes.versionSelect}
                  onClick={(e) => e.stopPropagation()}>
                  <VersionSelectComponent
                    currentVersion={props.plugin.currentVersion}
                    selectedVersion={props.plugin.version}
                    selectVersion={props.selectVersion}
                  />
                </div>
                <div className={classes.centerFlex} />
                <div
                  className={classes.options}
                  onClick={(e) => e.stopPropagation()}>
                  <Options
                    descriptor={props.plugin}
                    data={JSON.stringify(props.plugin.pluginData, null, '\t')}
                  />
                </div>
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

const ComposedPlugin: React.ComponentClass<IPublicProps, IPrivateProps> =
  compose<IPublicProps, IPrivateProps>(
    pure,
    connect(pluginSelector),
    setDisplayName('Plugin'),
  )(Plugin);

export default ComposedPlugin;
