import { makeStyles } from '@material-ui/core/styles';
import * as _ from 'lodash';
import * as React from 'react';
import { useSelector } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { isChatOpenedSelector } from '../../selectors/ChatSelectors';
import Plugin from '../PackageDetailView/PluginBoxes/Plugin';
import { IPackage } from '../utils/AxiosFunctions';
import clsx from 'clsx';
import modalActionDispatchers from '../../../scripts/actions/ModalActionDispatchers';
import { historyPush } from '../../../scripts/history';

const useStyles = makeStyles({
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
  pluginListColumn: {
    display: 'flex',
    flexDirection: 'column',
  },
});

interface IProps {
  packagePayload: IPackage;
  packageId?: string;
  botId?: string;
}

const PluginList: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  const { isOpened: isChatOpened } = useSelector(isChatOpenedSelector);

  const openParallelConfigModal = (pluginResource?: string) => {
    modalActionDispatchers.showParallelConfigModal(
      props.packagePayload,
      pluginResource,
    );
  };

  return (
    <div>
      {!!props.packagePayload.packageData &&
        !_.isEmpty(props.packagePayload.packageData.packageExtensions) && (
          <div
            className={clsx(
              classes.pluginList,
              isChatOpened ? classes.pluginListColumn : null,
            )}>
            {props.packagePayload.packageData.packageExtensions.map(
              (plug, key) => (
                <Plugin
                  key={key}
                  pluginType={plug}
                  pluginResource={plug.config.uri || ''}
                  editDisabled={true}
                  packageId={props.packageId}
                  botId={props.botId}
                  openParallelConfigModal={() => {
                    openParallelConfigModal(plug.config.uri);
                    historyPush(`${location.pathname}`, [
                      `packageId=${props.packageId}`,
                    ]);
                  }}
                />
              ),
            )}
          </div>
        )}
    </div>
  );
};

const ComposedPluginList: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PluginList'),
)(PluginList);

export default ComposedPluginList;
