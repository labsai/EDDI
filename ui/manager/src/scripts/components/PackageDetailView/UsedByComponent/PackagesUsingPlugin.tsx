import { makeStyles } from '@material-ui/core/styles';
import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { IPlugin } from '../../utils/AxiosFunctions';
import Parser, { IUsedResource } from '../../utils/Parser';
import Package from './Package';

const useStyles = makeStyles({
  content: {
    width: '100%',
  },
  seeMore: {
    display: 'inline-block',
    color: '#16325C',
    fontSize: '12px',
    marginTop: '12px',
    minWidth: 'fit-content',
  },
  list: {
    display: 'inline-block',
    minWidth: 'fit-content',
  },
});

interface IProps {
  plugin: IPlugin;
  isSmallName?: boolean;
}

const PackagesUsingPlugin = (props: IProps) => {
  const [expandList, setExpandList] = React.useState(false);

  const classes = useStyles();

  React.useEffect(() => {
    if (!_.isEmpty(props.plugin)) {
      eddiApiActionDispatchers.fetchPackagesUsingPluginAction(
        props.plugin.resource,
        false,
      );
    }
  }, [props.plugin, props.isSmallName]);

  React.useEffect(() => {
    if (!props.plugin.usedByPackages) {
      eddiApiActionDispatchers.fetchPackagesUsingPluginAction(
        props.plugin.resource,
        false,
      );
    }
  }, []);

  const handleExpandList = () => {
    setExpandList(!expandList);
  };

  let shortList: IUsedResource[];
  if (!_.isEmpty(props.plugin.usedByPackages)) {
    shortList = Parser.shortenResourceList(props.plugin.usedByPackages);
  }
  return (
    <div>
      {!_.isEmpty(props.plugin.usedByPackages) && (
        <div className={classes.content}>
          {expandList ? (
            <div className={classes.list}>
              {props.plugin.usedByPackages.map((resource) => (
                <Package
                  key={resource}
                  packageResource={resource}
                  isSmallName={!!props.isSmallName}
                />
              ))}
            </div>
          ) : (
            <div className={classes.list}>
              {shortList.map((r) => (
                <Package
                  key={r.resource}
                  packageResource={r.resource}
                  usedByOlderVersion={r.usedByOlderVersion}
                  isSmallName={!!props.isSmallName}
                />
              ))}
            </div>
          )}
          {_.size(props.plugin.usedByPackages) > _.size(shortList) &&
            !expandList && (
              <div className={classes.seeMore} onClick={handleExpandList}>
                {'...See more'}
              </div>
            )}
        </div>
      )}
      {_.size(props.plugin.usedByPackages) > _.size(shortList) && expandList && (
        <div className={classes.seeMore} onClick={handleExpandList}>
          {'See less'}
        </div>
      )}
    </div>
  );
};

const ComposedPackagesUsingPlugin: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('PackagesUsingPlugin'),
)(PackagesUsingPlugin);

export default ComposedPackagesUsingPlugin;
