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

interface IPublicProps {
  filterText: string;
}

interface IPrivateProps extends IPublicProps {
  bots: IBot[];
  isLoading: boolean;
  error: Error;
}

interface IState {
  apiUrl: string;
}

class BotList extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      apiUrl: '',
    };
  }

  async componentDidMount() {
    eddiApiActionDispatchers.fetchBotsAction();
    this.setState({ apiUrl: await getAPIUrl() });
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
  render() {
    const botList = this.filterBots();
    return (
      <div>
        {renderIf(this.props.isLoading)(() => (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        ))}
        {renderIf(!this.props.isLoading)(() => (
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
                {botList.map(bot => (
                  <Bot
                    key={bot.resource}
                    bot={bot}
                    apiUrl={this.state.apiUrl}
                  />
                ))}
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
