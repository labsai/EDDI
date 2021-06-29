import { makeStyles } from '@material-ui/core/styles';
import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { WHITE_COLOR } from '../../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { IPackage } from '../../utils/AxiosFunctions';
import Parser, { IUsedResource } from '../../utils/Parser';
import Bot from './Bot';

interface IProps {
  packagePayload: IPackage;
  isSmallName?: boolean;
}

const DELAY = 1000;

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

const BotsUsingPackage = ({ packagePayload, isSmallName }: IProps) => {
  const [expandList, setExpandList] = React.useState(false);
  const classes = useStyles();

  React.useEffect(() => {
    let timer: NodeJS.Timeout = null;
    if (!_.isEmpty(packagePayload)) {
      timer = setTimeout(() => {
        eddiApiActionDispatchers.fetchBotsUsingPackageAction(
          packagePayload.resource,
          false,
        );
      }, DELAY);
    }
    return () => clearTimeout(timer);
  }, [packagePayload.resource, isSmallName]);

  const handleExpandList = () => {
    setExpandList(!expandList);
  };

  let shortList: IUsedResource[];
  if (!_.isEmpty(packagePayload.usedByBots)) {
    shortList = Parser.shortenResourceList(packagePayload.usedByBots);
  }
  return (
    <div>
      {!_.isEmpty(packagePayload.usedByBots) && (
        <div className={classes.content}>
          {expandList ? (
            <div className={classes.list}>
              {packagePayload.usedByBots.map((resource) => (
                <Bot
                  key={resource}
                  botResource={resource}
                  isSmallName={!!isSmallName}
                />
              ))}
            </div>
          ) : (
            <div className={classes.list}>
              {shortList.map((r) => (
                <Bot
                  key={r.resource}
                  botResource={r.resource}
                  usedByOlderVersion={r.usedByOlderVersion}
                  isSmallName={!!isSmallName}
                />
              ))}
            </div>
          )}
          {_.size(packagePayload.usedByBots) > _.size(shortList) &&
            !expandList && (
              <div className={classes.seeMore} onClick={handleExpandList}>
                {'...See more'}
              </div>
            )}
        </div>
      )}
      {_.size(packagePayload.usedByBots) > _.size(shortList) && expandList && (
        <div className={classes.seeMore} onClick={handleExpandList}>
          {'See less'}
        </div>
      )}
    </div>
  );
};

const ComposedBotsUsingPackage: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('BotsUsingPackage'),
)(BotsUsingPackage);

export default ComposedBotsUsingPackage;
