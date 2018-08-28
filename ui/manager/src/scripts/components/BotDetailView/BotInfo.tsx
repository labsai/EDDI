import * as React from 'react';
import BotView from './BotView';
import { IBot } from '../utils/AxiosFunctions';
import { Component, compose, pure, setDisplayName } from 'recompose';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import * as Radium from 'radium';
import { latestBotSelector } from '../../selectors/BotSelectors';
import { connect } from 'react-redux';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import styles from '../Bots/Botlist.styles';
import * as _ from 'lodash';
import { ClimbingBoxLoader } from 'react-spinners';
import * as renderIf from 'render-if';
import Parser from '../utils/Parser';

interface IPublicProps {
  botId: string;
}

interface IPrivateProps extends IPublicProps {
  bot: IBot;
  error: Error;
  isLoading: boolean;
}

interface IState {
  selectedBotResource: string;
}

class BotInfo extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedBotResource: '',
    };
  }

  componentDidMount() {
    if (!this.props.isLoading && !this.props.bot) {
      eddiApiActionDispatchers.fetchBotAction(this.props.botId);
    }
    if (this.props.bot) {
      this.setState({
        selectedBotResource: this.props.bot.resource,
      });
    }
  }
  componentWillReceiveProps(nextProps) {
    if (!_.isEmpty(this.props.bot) && !_.isEmpty(nextProps.bot)) {
      if (nextProps.bot.currentVersion > this.props.bot.currentVersion) {
        this.setState({
          selectedBotResource: nextProps.bot.resource,
        });
      }
    } else if (!_.isEmpty(nextProps.bot)) {
      this.setState({
        selectedBotResource: nextProps.bot.resource,
      });
    }
  }

  selectVersion = (resource: string, newVersion: number) => {
    const selectedBotResource = Parser.replaceResourceVersion(
      resource,
      newVersion,
    );
    this.setState({
      selectedBotResource,
    });
    eddiApiActionDispatchers.fetchBotAction(selectedBotResource);
  };

  render() {
    return (
      <div>
        <HomeButtonComponent />
        {renderIf(this.props.isLoading)(() => (
          <div style={styles.loadingWrapper}>
            <ClimbingBoxLoader loading />
          </div>
        ))}
        {renderIf(!this.props.isLoading)(() => (
          <div>
            {renderIf(this.props.error)(() => (
              <p>{'Error: Could not load bot'}</p>
            ))}
            {renderIf(!this.props.error && _.isEmpty(this.props.bot))(() => (
              <p>{'Bot not found'}</p>
            ))}
            {renderIf(!this.props.error && !_.isEmpty(this.props.bot))(() => (
              <BotView
                botResource={this.state.selectedBotResource}
                selectVersion={this.selectVersion}
              />
            ))}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedBotInfo: Component<IProps> = compose<IProps>(
  pure,
  Radium,
  connect(latestBotSelector),
  setDisplayName('BotInfo'),
)(BotInfo);

export default ComposedBotInfo;
