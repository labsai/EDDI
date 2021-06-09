import * as _ from 'lodash';
import * as React from 'react';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import { connect } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { botsSelector } from '../../selectors/BotSelectors';
import BlueButton from '../Assets/Buttons/BlueButton';
import { getAPIUrl } from '../utils/ApiFunctions';
import { IBot } from '../utils/AxiosFunctions';
import Bot from './Bot';
import useStyles from './Botlist.styles';
const InfiniteScroll =
  require('react-infinite-scroller') as InfiniteScrollTypes;

interface IPublicProps {
  filterText: string;
}

interface IPrivateProps extends IPublicProps {
  bots: IBot[];
  isLoading: boolean;
  allBotsLoaded: boolean;
  error: Error;
  botsLoaded: number;
}

const BotList = ({
  isLoading,
  bots,
  allBotsLoaded,
  error,
  botsLoaded,
  filterText,
}: IPrivateProps) => {
  const [apiUrl, setApiUrl] = React.useState('');
  const [loading, setLoading] = React.useState(false);

  const classes = useStyles();

  const asyncSetApiUrl = async () => {
    const apiUrl = await getAPIUrl();
    setApiUrl(apiUrl);
  };

  const loadMore = () => {
    if (loading) {
      return;
    }
    setLoading(true);
    const limit = 5;
    if (bots.length < limit && !allBotsLoaded) {
      eddiApiActionDispatchers.fetchBotsAction(limit, 0);
    } else {
      eddiApiActionDispatchers.fetchBotsAction(
        limit,
        Math.floor(botsLoaded / limit),
      );
    }
  };

  React.useEffect(() => {
    if (!isLoading) {
      loadMore();
    }
    asyncSetApiUrl();
  }, []);

  React.useEffect(() => {
    if (!isLoading) {
      setLoading(false);
    }
  }, [isLoading]);

  const filterBots = () => {
    if (!_.isEmpty(filterText)) {
      return bots.filter(
        (bot) =>
          bot.name.toLowerCase().includes(filterText.toLowerCase()) ||
          bot.id.toLowerCase().includes(filterText.toLowerCase()),
      );
    } else {
      return bots;
    }
  };

  const botList = filterBots();
  return (
    <div>
      {isLoading && _.isEmpty(bots) && (
        <div className={classes.loadingWrapper}>
          <ClimbingBoxLoader loading />
        </div>
      )}
      <div>
        {!!error && !isLoading && <p>{'Error: Could not load bots'}</p>}
        {!error && !isLoading && _.isEmpty(bots) && (
          <div>
            <div>{`There are no bots yet..`}</div>
            <BlueButton
              classes={{ button: classes.deployExampleBotsButton }}
              onClick={() => eddiApiActionDispatchers.deployExampleBotsAction()}
              text={'Deploy Example Bots'}
            />
          </div>
        )}
        {!error && !_.isEmpty(bots) && (
          <div>
            {_.isEmpty(botList) && (
              <p>{`Found no bots matching: "${filterText}"`}</p>
            )}
            <InfiniteScroll
              pageStart={0}
              loadMore={loadMore}
              hasMore={!allBotsLoaded && !isLoading}
              loader={
                <div className="loader" key={0}>
                  Loading ...
                </div>
              }>
              {botList.map((bot) => (
                <Bot key={bot.resource} bot={bot} apiUrl={apiUrl} />
              ))}
            </InfiniteScroll>
          </div>
        )}
      </div>
    </div>
  );
};

const ComposedBotList: React.ComponentClass<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(
  pure,
  connect(botsSelector),
  setDisplayName('BotList'),
)(BotList);

export default ComposedBotList;
