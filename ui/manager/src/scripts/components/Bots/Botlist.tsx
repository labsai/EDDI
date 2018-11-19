import * as React from 'react';
import Bot from './Bot';
import * as renderIf from 'render-if';
import * as _ from 'lodash';
import * as Radium from 'radium';
import { Component, compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { IBot } from '../utils/AxiosFunctions';
import { connect } from 'react-redux';
import { botsSelector } from '../../selectors/BotSelectors';
import styles from './Botlist.styles';
import { ClimbingBoxLoader } from 'react-spinners';
import { getAPIUrl } from '../utils/ApiFunctions';
import * as InfiniteScrollTypes from 'react-infinite-scroller';
const InfiniteScroll = require('react-infinite-scroller') as InfiniteScrollTypes;

interface IPublicProps {
  filterText: string;
}

interface IPrivateProps extends IPublicProps {
  bots: IBot[];
  isLoading: boolean;
  allBotsLoaded: boolean;
  error: Error;
}

interface IState {
  apiUrl: string;
  loading: boolean;
  page: number;
}

class BotList extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      apiUrl: '',
      loading: false,
      page: 0,
    };
  }

  async componentDidMount() {
    eddiApiActionDispatchers.fetchBotsAction(12, 0);
    this.setState({ apiUrl: await getAPIUrl() });
  }

  componentWillReceiveProps(nextProps) {
    if (this.props.isLoading && !nextProps.isLoading) {
      this.setState({ loading: false });
    }
  }

  filterBots() {
    if (!_.isEmpty(this.props.filterText)) {
      return this.props.bots.filter(
        bot =>
          bot.name
            .toLowerCase()
            .includes(this.props.filterText.toLowerCase()) ||
          bot.id.toLowerCase().includes(this.props.filterText.toLowerCase()),
      );
    } else {
      return this.props.bots;
    }
  }

  loadMore = () => {
    if (this.state.loading) {
      return;
    }
    this.setState({ loading: true, page: this.state.page + 1 });
    eddiApiActionDispatchers.fetchBotsAction(12, this.state.page);
  };

  render() {
    const botList = this.filterBots();
    return (
      <div>
        {renderIf(false)(() => (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        ))}
        {renderIf(true)(() => (
          <div>
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load bots'}</p>
            ))}
            {renderIf(!this.props.error && _.isEmpty(this.props.bots))(() => (
              <p>{`There are no bots yet`}</p>
            ))}
            {renderIf(!this.props.error && !_.isEmpty(this.props.bots))(() => (
              <div>
                {renderIf(_.isEmpty(botList))(() => (
                  <p>{`Found no bots matching: "${this.props.filterText}"`}</p>
                ))}
                <InfiniteScroll
                  pageStart={0}
                  loadMore={this.loadMore}
                  hasMore={!this.props.allBotsLoaded && !this.props.isLoading}
                  loader={
                    <div className="loader" key={0}>
                      Loading ...
                    </div>
                  }>
                  {botList.map(bot => (
                    <Bot
                      key={bot.resource}
                      bot={bot}
                      apiUrl={this.state.apiUrl}
                    />
                  ))}
                </InfiniteScroll>
              </div>
            ))}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedBotList: Component<IPublicProps> = compose<
  IPublicProps,
  IPrivateProps
>(pure, Radium, connect(botsSelector), setDisplayName('BotList'))(BotList);

export default ComposedBotList;
