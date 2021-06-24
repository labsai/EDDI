import * as _ from 'lodash';
import * as moment from 'moment';
import * as React from 'react';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
import { useDispatch, useSelector } from 'react-redux';
import ClimbingBoxLoader from 'react-spinners/ClimbingBoxLoader';
import {
  fetchBotLogsAction,
  resetBotLogs,
} from '../../../actions/EddiApiActions';
import { botLogsSelector } from '../../../selectors/BotSelectors';
import { IBot, IBotLogs } from '../../utils/AxiosFunctions';
import '../ModalComponent.styles.scss';
import useStyles from './BotLogsModal.styles';

const InfiniteScroll =
  require('react-infinite-scroller') as InfiniteScrollTypes;

interface IPublicProps {
  bot: IBot;
}

const LIMIT = 10;

const BotLogsModal = ({ bot }: IPublicProps) => {
  const classes = useStyles();
  const logsContainerRef = React.useRef<HTMLDivElement>(null);

  const { logs, error, isLoadingBotLogs } = useSelector(botLogsSelector);
  const [lastIndex, setLastIndex] = React.useState(10);

  const loadMore = () => {
    setLastIndex(lastIndex + LIMIT);
  };

  const dispatch = useDispatch();

  React.useEffect(() => {
    dispatch(resetBotLogs());
    dispatch(fetchBotLogsAction(bot.id, bot.version, 'unrestricted')); // todo replace with real environment
  }, [bot]);

  const newLogs = logs?.slice(0, lastIndex);
  const reachedLast = lastIndex >= logs?.length;

  return (
    <form className={classes.modalContainer}>
      <div className={classes.modalHeader}>
        <div className={classes.modalTopHeader}>
          {'Logs for the current bot'}
        </div>
      </div>
      <div className={classes.logsContainer}>
        {isLoadingBotLogs && (
          <div className={classes.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        )}
        {!!error && <p>{'Error: Could not load bot logs'}</p>}
        {_.isEmpty(logs) && !isLoadingBotLogs && <p>{'Logs are empty'}</p>}
        {!error && !isLoadingBotLogs && !_.isEmpty(logs) && (
          <div className={classes.content} ref={logsContainerRef}>
            <InfiniteScroll
              pageStart={0}
              loadMore={loadMore}
              useWindow={false}
              hasMore={!reachedLast && !isLoadingBotLogs}
              getScrollParent={() => logsContainerRef.current}
              loader={
                <div className="loader" key={0}>
                  Loading ...
                </div>
              }>
              {newLogs.map((l: IBotLogs, i: number) => {
                return (
                  <div key={l.botId + i} className={classes.logItem}>
                    <p>
                      <strong>
                        {moment(l.timestamp).format('hh:mm:ss DD/MM/YYYY')}
                        {': '}
                      </strong>
                      {l.message}
                    </p>
                  </div>
                );
              })}
            </InfiniteScroll>
          </div>
        )}
      </div>
    </form>
  );
};

export default BotLogsModal;
