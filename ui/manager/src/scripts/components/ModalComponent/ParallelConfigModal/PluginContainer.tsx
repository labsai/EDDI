import * as _ from 'lodash';
import * as React from 'react';
import { useSelector } from 'react-redux';
import eddiApiActionDispatchers from '../../../../scripts/actions/EddiApiActionDispatchers';
import { IAppState } from 'src/scripts/reducers';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import { getPlugin, IPlugin } from '../../utils/AxiosFunctions';
import EditJsonModal from '../EditJsonModal/EditJsonModal';
import ViewJsonContent from '../ViewJsonModal/ViewJsonContent';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';

import { makeStyles } from '@material-ui/core/styles';

const useStyles = makeStyles({
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
  deployExampleBotsButton: {
    marginTop: '5px',
    width: '160px',
  },
});

interface IPublicProps {
  pluginResource: string;
  type: string;
}

const PluginContainer = (props: IPublicProps) => {
  const classes = useStyles();
  const [isEdit, setIsEdit] = React.useState(true);

  const [plugin, setPlugin] = React.useState<IPlugin>();

  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState(null);

  React.useEffect(() => {
    setLoading(true);
    getPlugin(props.pluginResource)
      .then((res) => {
        setLoading(false);
        setPlugin(res);
        setError(null);
      })
      .catch((error) => {
        setError(error);
        setLoading(false);
      });
  }, []);

  return (
    <div>
      {loading ? (
        <div className={classes.loadingWrapper}>
          <ClimbingBoxLoader loading color="white" />
        </div>
      ) : (
        <div>
          {!!error && <p>{'Error: Could not load plugin'}</p>}
          {!error && !plugin && <p>{'Plugin not found'}</p>}
          {!error &&
            plugin &&
            (!isEdit ? (
              <ViewJsonContent
                descriptor={plugin as IPlugin}
                data={JSON.stringify(plugin.pluginData, null, '\t')}
                usedBy={(plugin as IPlugin).usedByPackages}
                showEditJson={() => setIsEdit(true)}
              />
            ) : (
              <EditJsonModal
                type={props.type}
                descriptor={plugin as IPlugin}
                resource={(plugin as IPlugin).resource}
                data={JSON.stringify(plugin.pluginData, null, '\t')}
                showViewJson={() => setIsEdit(false)}
              />
            ))}
        </div>
      )}
      {}
    </div>
  );
};

export default PluginContainer;
