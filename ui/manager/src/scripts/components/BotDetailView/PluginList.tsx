import { makeStyles } from '@material-ui/core/styles';
import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import Plugin from '../PackageDetailView/PluginBoxes/Plugin';
import { IPackage } from '../utils/AxiosFunctions';

const useStyles = makeStyles({
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
});

interface IProps {
  packagePayload: IPackage;
}

const PluginList: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  return (
    <div>
      {!!props.packagePayload.packageData &&
        !_.isEmpty(props.packagePayload.packageData.packageExtensions) && (
          <div className={classes.pluginList}>
            {props.packagePayload.packageData.packageExtensions.map(
              (plug, key) => (
                <Plugin
                  key={key}
                  pluginType={plug}
                  pluginResource={plug.config.uri || ''}
                  editDisabled={true}
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
