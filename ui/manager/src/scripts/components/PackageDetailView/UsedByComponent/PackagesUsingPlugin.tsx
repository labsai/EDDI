import { makeStyles } from '@material-ui/core/styles';
import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { WHITE_COLOR } from '../../../../styles/DefaultStylingProperties';
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
    color: WHITE_COLOR,
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

const PackagesUsingPlugin = ({ plugin, isSmallName }: IProps) => {
  const [expandList, setExpandList] = React.useState(false);
  const [usedByPackagesShort, setUsedByPackagesShort] =
    React.useState<IUsedResource[]>(null);
  const [usedByPackages, setUsedByPackages] = React.useState<string[]>(null);

  const classes = useStyles();

  React.useEffect(() => {
    eddiApiActionDispatchers.fetchPackagesUsingPluginAction(
      plugin.resource,
      false,
    );
  }, []);

  React.useEffect(() => {
    if (!_.isEmpty(plugin.usedByPackages)) {
      setUsedByPackagesShort(Parser.shortenResourceList(plugin.usedByPackages));
      setUsedByPackages(plugin.usedByPackages);
    }
  }, [plugin.usedByPackages]);

  const handleExpandList = () => {
    setExpandList(!expandList);
  };

  return (
    <div>
      {!_.isEmpty(usedByPackages) && (
        <div className={classes.content}>
          {expandList ? (
            <div className={classes.list}>
              {usedByPackages.map((resource) => (
                <Package
                  key={resource}
                  packageResource={resource}
                  isSmallName={!!isSmallName}
                />
              ))}
            </div>
          ) : (
            <div className={classes.list}>
              {usedByPackagesShort.map((r) => (
                <Package
                  key={r.resource}
                  packageResource={r.resource}
                  usedByOlderVersion={r.usedByOlderVersion}
                  isSmallName={!!isSmallName}
                />
              ))}
            </div>
          )}
          {_.size(usedByPackages) > _.size(usedByPackagesShort) && !expandList && (
            <div className={classes.seeMore} onClick={handleExpandList}>
              {'...See more'}
            </div>
          )}
        </div>
      )}
      {_.size(usedByPackages) > _.size(usedByPackagesShort) && expandList && (
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
