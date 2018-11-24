import * as React from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import * as Radium from 'radium';
import { CSSProperties } from 'react';
import * as _ from 'lodash';
import Bot from './Bot';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import * as renderIf from 'render-if';
import { IPackage } from '../../utils/AxiosFunctions';
import { IUsedResource } from '../../utils/Parser';
import Parser from '../../utils/Parser';

interface IProps {
  packagePayload: IPackage;
  isSmallName: boolean;
}

interface IState {
  expandList: boolean;
}

const styles: CSSProperties = {
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
};

class BotsUsingPackage extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      expandList: false,
    };
  }

  componentDidMount() {
    if (!_.isEmpty(this.props.packagePayload)) {
      eddiApiActionDispatchers.fetchBotsUsingPackageAction(
        this.props.packagePayload.resource,
        false,
      );
    }
  }

  componentWillReceiveProps(nextProps) {
    if (!nextProps.packagePayload.usedByBots) {
      eddiApiActionDispatchers.fetchBotsUsingPackageAction(
        nextProps.packagePayload.resource,
        false,
      );
    }
  }

  expandList = () => {
    this.setState({ expandList: !this.state.expandList });
  };

  render() {
    let shortList: IUsedResource[];
    if (!_.isEmpty(this.props.packagePayload.usedByBots)) {
      shortList = Parser.shortenResourceList(
        this.props.packagePayload.usedByBots,
      );
    }
    return (
      <div>
        {renderIf(!_.isEmpty(this.props.packagePayload.usedByBots))(() => (
          <div style={styles.content}>
            {renderIf(this.state.expandList)(() => (
              <div style={styles.list}>
                {this.props.packagePayload.usedByBots.map(resource => (
                  <Bot
                    key={resource}
                    botResource={resource}
                    isSmallName={!!this.props.isSmallName}
                  />
                ))}
              </div>
            ))}
            {renderIf(!this.state.expandList)(() => (
              <div style={styles.list}>
                {shortList.map(r => (
                  <Bot
                    key={r.resource}
                    botResource={r.resource}
                    usedByOlderVersion={r.usedByOlderVersion}
                    isSmallName={!!this.props.isSmallName}
                  />
                ))}
              </div>
            ))}
            {renderIf(
              _.size(this.props.packagePayload.usedByBots) >
                _.size(shortList) && !this.state.expandList,
            )(() => (
              <div style={styles.seeMore} onClick={this.expandList}>
                {'...See more'}
              </div>
            ))}
          </div>
        ))}
        {renderIf(
          _.size(this.props.packagePayload.usedByBots) > _.size(shortList) &&
            this.state.expandList,
        )(() => (
          <div style={styles.seeMore} onClick={this.expandList}>
            {'See less'}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedBotsUsingPackage: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('BotsUsingPackage'),
)(BotsUsingPackage);

export default ComposedBotsUsingPackage;
